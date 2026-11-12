package blue.anika.wolle.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AdminUserDto
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.AdminException
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.data.repository.AdminRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.DangerousActionGuard
import blue.anika.wolle.ui.common.MutationFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The server requires at least 12 characters. */
const val MIN_PASSWORD_LENGTH = 12

private const val MAX_USER_PAGES = 20

// No look-alike characters (0/O, 1/l/I) so a password read out loud or typed from a screen is safe.
private const val PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** A random temporary password, at least [MIN_PASSWORD_LENGTH] characters long. */
fun generateTemporaryPassword(
    length: Int = 16,
    random: java.util.Random = SecureRandom(),
): String = buildString {
    repeat(length.coerceAtLeast(MIN_PASSWORD_LENGTH)) {
        append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)])
    }
}

/**
 * Whether [user] is the signed-in account: by server id when the server reported it (v2 meta),
 * otherwise by email (a password sign-in remembers it).
 */
fun isCurrentUser(user: AdminUserDto, currentEmail: String?, currentUserId: Long? = null): Boolean =
    if (currentUserId != null) user.id == currentUserId
    else currentEmail != null && user.email.equals(currentEmail, ignoreCase = true)

sealed interface AccountsPhase {
    data object Loading : AccountsPhase

    data object Ready : AccountsPhase

    data class Failed(val failure: AdminFailure) : AccountsPhase
}

data class AccountsState(
    val users: List<AdminUserDto> = emptyList(),
    val phase: AccountsPhase = AccountsPhase.Loading,
    val isRefreshing: Boolean = false,
    val currentEmail: String? = null,
    val currentUserId: Long? = null,
    /** User whose Disable/Enable request is in flight. */
    val busyUserId: Long? = null,
)

/** State of a form that talks to the server (add account, reset password). */
sealed interface FormState {
    data object Idle : FormState

    data object Saving : FormState

    data class Failed(val failure: AdminFailure) : FormState
}

@HiltViewModel
class AdminAccountsViewModel
@Inject
constructor(
    private val repository: AdminRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val feedback: MutationFeedback,
    private val settings: SettingsRepository,
    private val guard: DangerousActionGuard,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            AccountsState(
                currentEmail = repository.currentEmail,
                currentUserId = repository.currentUserId,
            )
        )
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    private val _form = MutableStateFlow<FormState>(FormState.Idle)
    val form: StateFlow<FormState> = _form.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(userInitiated = false)
    }

    fun avatarUrl(path: String?): String? = mediaUrlResolver.resolve(path)

    /**
     * Where the web app opens the "Impersonate this account?" confirmation for [user], or null
     * where the web hides the action (yourself, administrators, disabled accounts) or there is no
     * web app (local-only mode).
     */
    fun impersonationLink(user: AdminUserDto): String? {
        val current = _state.value
        val index = current.users.indexOfFirst { it.id == user.id }
        if (settings.isLocalMode || index < 0) return null
        if (
            !canImpersonate(user, isCurrentUser(user, current.currentEmail, current.currentUserId))
        ) {
            return null
        }
        return impersonationUrl(settings.credentials.value.serverUrl, user.id, index)
    }

    fun refresh() = load(userInitiated = true)

    fun retry() = load(userInitiated = false)

    private fun load(userInitiated: Boolean) {
        loadJob?.cancel()
        _state.update {
            it.copy(
                isRefreshing = userInitiated,
                phase = if (it.users.isEmpty()) AccountsPhase.Loading else it.phase,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val users = mutableListOf<AdminUserDto>()
                var page = 1
                do {
                    val result = repository.users(page)
                    users += result.data
                    page = result.meta.page + 1
                } while (result.meta.hasMore && page <= MAX_USER_PAGES)
                _state.update {
                    it.copy(
                        users = users,
                        phase = AccountsPhase.Ready,
                        isRefreshing = false,
                        currentEmail = repository.currentEmail,
                        currentUserId = repository.currentUserId,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val why = (failure as? AdminException)?.failure ?: AdminFailure.Other
                _state.update {
                    if (it.users.isEmpty()) {
                        it.copy(phase = AccountsPhase.Failed(why), isRefreshing = false)
                    } else {
                        feedback.show(why.messageRes())
                        it.copy(isRefreshing = false)
                    }
                }
            }
        }
    }

    /** Disabling an account is guarded by the device unlock; enabling one is not. */
    fun setActive(user: AdminUserDto, active: Boolean) {
        if (active) setActiveConfirmed(user, true)
        else guard.confirm(R.string.unlock_reason_disable_user) { setActiveConfirmed(user, false) }
    }

    private fun setActiveConfirmed(user: AdminUserDto, active: Boolean) {
        if (_state.value.busyUserId != null) return
        _state.update { it.copy(busyUserId = user.id) }
        viewModelScope.launch {
            try {
                val updated = repository.setActive(user.id, active)
                _state.update { s ->
                    s.copy(
                        users = s.users.map { if (it.id == updated.id) updated else it },
                        busyUserId = null,
                    )
                }
                feedback.show(
                    if (active) R.string.admin_accounts_enabled
                    else R.string.admin_accounts_disabled,
                    user.email,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _state.update { it.copy(busyUserId = null) }
                feedback.show(
                    ((failure as? AdminException)?.failure ?: AdminFailure.Other).messageRes()
                )
            }
        }
    }

    fun createUser(email: String, password: String, isAdmin: Boolean, onDone: () -> Unit) {
        if (_form.value == FormState.Saving) return
        _form.value = FormState.Saving
        viewModelScope.launch {
            try {
                val created = repository.createUser(email, password, isAdmin)
                _state.update { it.copy(users = it.users + created) }
                _form.value = FormState.Idle
                feedback.show(R.string.admin_accounts_created, created.email)
                onDone()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _form.value =
                    FormState.Failed((failure as? AdminException)?.failure ?: AdminFailure.Other)
            }
        }
    }

    fun resetPassword(user: AdminUserDto, password: String, onDone: () -> Unit) =
        guard.confirm(R.string.unlock_reason_reset_password) {
            resetPasswordConfirmed(user, password, onDone)
        }

    private fun resetPasswordConfirmed(user: AdminUserDto, password: String, onDone: () -> Unit) {
        if (_form.value == FormState.Saving) return
        _form.value = FormState.Saving
        viewModelScope.launch {
            try {
                repository.resetPassword(user.id, password)
                _form.value = FormState.Idle
                feedback.show(R.string.admin_accounts_password_reset, user.email)
                onDone()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _form.value =
                    FormState.Failed((failure as? AdminException)?.failure ?: AdminFailure.Other)
            }
        }
    }

    /** Clears an old form error when a dialog or sheet opens or closes. */
    fun clearForm() {
        _form.value = FormState.Idle
    }

    fun copied() = feedback.show(R.string.admin_accounts_copied)
}
