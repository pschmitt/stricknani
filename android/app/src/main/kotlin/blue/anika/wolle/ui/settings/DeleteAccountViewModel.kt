package blue.anika.wolle.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.account.AccountDeletionGateway
import blue.anika.wolle.data.account.DeleteAccountResult
import blue.anika.wolle.data.account.deletionConfirmationMatches
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcEvent
import blue.anika.wolle.data.oidc.OidcIdentity
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.DangerousActionGuard
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.oidc.DEFAULT_PROVIDER_NAME
import blue.anika.wolle.ui.oidc.ReauthRequest
import blue.anika.wolle.ui.oidc.ReauthUiState
import blue.anika.wolle.ui.oidc.StepUp
import blue.anika.wolle.ui.oidc.showOidcFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeleteAccountState(
    val open: Boolean = false,
    /** What the user typed: `DELETE` or their email. Survives the trip to the login provider. */
    val confirmation: String = "",
    /** A deletion request is in flight. */
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    /** The server deleted the account: the screen wipes the local copy and leaves. */
    val deleted: Boolean = false,
)

/**
 * Settings → Account → Delete account. Asks for a typed confirmation, then for a fresh "it is you"
 * (password, or the linked login provider, through the same [StepUp] as linking a login), and only
 * then sends the one-use proof with the request. Local data is wiped by the screen once
 * [DeleteAccountState.deleted] flips, so the app returns to onboarding exactly like a sign-out.
 */
@HiltViewModel
class DeleteAccountViewModel
internal constructor(
    private val gateway: AccountDeletionGateway,
    private val oidc: OidcCoordinator,
    private val accountEmail: () -> String?,
    private val serverUrl: () -> String,
    private val feedback: MutationFeedback,
    private val guard: DangerousActionGuard,
) : ViewModel() {
    @Inject
    constructor(
        gateway: AccountDeletionGateway,
        oidc: OidcCoordinator,
        settings: SettingsRepository,
        feedback: MutationFeedback,
        guard: DangerousActionGuard,
    ) : this(
        gateway,
        oidc,
        { settings.accountEmail.value },
        { settings.credentials.value.serverUrl },
        feedback,
        guard,
    )

    private val _state = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    /** The email the user may type instead of `DELETE`. */
    val email: String
        get() = accountEmail().orEmpty()

    private var providers: List<OidcProvider> = emptyList()
    private var identities: List<OidcIdentity> = emptyList()
    private var passwordEnabled = true

    private val stepUp =
        StepUp(
            oidc,
            viewModelScope,
            onProof = ::proceed,
            onFailure = { failure ->
                feedback.showOidcFailure(
                    failure,
                    providers.firstOrNull()?.name ?: DEFAULT_PROVIDER_NAME,
                )
            },
        )
    val sheet: StateFlow<ReauthUiState?> = stepUp.sheet

    init {
        viewModelScope.launch { oidc.events.collect { event -> event?.let { onEvent(it) } } }
    }

    val confirmationValid: Boolean
        get() = deletionConfirmationMatches(_state.value.confirmation, email)

    fun open() {
        _state.value = DeleteAccountState(open = true)
        loadSignInMethods()
    }

    fun dismiss() {
        if (_state.value.busy) return
        _state.value = DeleteAccountState()
    }

    fun onConfirmationChange(value: String) = _state.update {
        it.copy(confirmation = value.take(MAX_CONFIRMATION_LENGTH), error = null)
    }

    /**
     * The typed confirmation is right: behind the device unlock, ask for the password (or provider)
     * and delete. Without a successful unlock nothing is sent.
     */
    fun confirm() {
        if (_state.value.busy || !confirmationValid) return
        guard.confirm(R.string.unlock_reason_delete_account) { beginReauth() }
    }

    private fun beginReauth() {
        _state.update { it.copy(error = null) }
        stepUp.begin(
            ReauthRequest(
                action = ReauthAction.DELETE_ACCOUNT,
                target = ReauthTarget.None,
                canUsePassword = passwordEnabled,
                // Provider confirmation needs a login the account already has.
                oidcProvider =
                    identities.firstNotNullOfOrNull { id ->
                        providers.firstOrNull { it.id == id.provider }
                    },
            )
        )
    }

    fun dismissSheet() = stepUp.dismiss()

    fun submitPassword(password: String) = stepUp.submitPassword(password)

    fun confirmWithProvider() = stepUp.useProvider()

    /** Confirms with one of the account's passkeys (offered by the sheet when it has one). */
    fun confirmWithPasskey(host: blue.anika.wolle.data.passkey.PasskeyHost) = stepUp.usePasskey(host)

    /** The screen has wiped the local data; forget the finished deletion. */
    fun acknowledgeDeleted() {
        _state.value = DeleteAccountState()
    }

    private fun loadSignInMethods() {
        viewModelScope.launch {
            // Unknown (older server, offline): assume a password, which is what most accounts have.
            providers = runCatching { oidc.providers(serverUrl()) }.getOrDefault(emptyList())
            if (providers.isEmpty()) {
                identities = emptyList()
                passwordEnabled = true
                return@launch
            }
            val envelope = oidc.loadIdentities().getOrNull()
            identities = envelope?.data.orEmpty()
            passwordEnabled = envelope?.meta?.passwordEnabled ?: true
        }
    }

    private suspend fun proceed(proof: ReauthProof) {
        val typed = _state.value.confirmation
        _state.update { it.copy(busy = true, error = null) }
        val result = gateway.deleteAccount(typed, proof)
        _state.update {
            when (result) {
                DeleteAccountResult.Deleted -> it.copy(busy = false, deleted = true)
                else -> it.copy(busy = false, error = result.messageRes())
            }
        }
    }

    private suspend fun onEvent(event: OidcEvent) {
        stepUp.handle(event)
    }

    private companion object {
        const val MAX_CONFIRMATION_LENGTH = 320
    }
}

/** The inline message for a refused deletion; [DeleteAccountResult.Deleted] has none. */
@StringRes
internal fun DeleteAccountResult.messageRes(): Int =
    when (this) {
        DeleteAccountResult.Deleted -> R.string.delete_account_error_generic
        DeleteAccountResult.ReauthRequired -> R.string.delete_account_error_reauth
        DeleteAccountResult.ConfirmationRejected -> R.string.delete_account_error_confirmation
        DeleteAccountResult.LastAdministrator -> R.string.delete_account_error_last_admin
        DeleteAccountResult.RateLimited -> R.string.delete_account_error_rate_limited
        DeleteAccountResult.SessionExpired -> R.string.delete_account_error_session
        DeleteAccountResult.Unsupported -> R.string.delete_account_error_unsupported
        is DeleteAccountResult.Failed ->
            if (offline) R.string.delete_account_error_offline
            else R.string.delete_account_error_generic
    }
