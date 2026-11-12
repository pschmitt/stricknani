package blue.anika.wolle.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AdminUserDto
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcFailure
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

data class AdminIdentitiesState(
    /** The server lists a sign-in provider; without one the menu entry is not offered. */
    val available: Boolean = false,
    val providers: List<OidcProvider> = emptyList(),
    /** The account whose sheet is open, or null. */
    val user: AdminUserDto? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val identities: List<OidcIdentity> = emptyList(),
    val confirmUnlink: OidcIdentity? = null,
    val working: Boolean = false,
) {
    fun providerName(id: String): String =
        providers.firstOrNull { it.id == id }?.name ?: id.replaceFirstChar { it.uppercase() }
}

/**
 * Admin → Accounts → a user's linked logins, and unlinking one. The unlink needs a fresh
 * `admin_unlink_identity` proof bound to that user and identity; an admin token alone does not
 * bypass it.
 */
@HiltViewModel
class AdminIdentitiesViewModel
@Inject
constructor(
    private val oidc: OidcCoordinator,
    private val settings: SettingsRepository,
    private val feedback: MutationFeedback,
    private val guard: DangerousActionGuard,
) : ViewModel() {
    private val _state = MutableStateFlow(AdminIdentitiesState())
    val state: StateFlow<AdminIdentitiesState> = _state.asStateFlow()

    private var pendingUnlink: OidcIdentity? = null

    private val stepUp =
        StepUp(
            oidc,
            viewModelScope,
            onProof = ::unlinkWith,
            onFailure = { feedback.showOidcFailure(it, providerName()) },
        )
    val sheet: StateFlow<ReauthUiState?> = stepUp.sheet

    init {
        viewModelScope.launch {
            val providers = oidc.providers(settings.credentials.value.serverUrl)
            _state.update { it.copy(available = providers.isNotEmpty(), providers = providers) }
        }
        viewModelScope.launch {
            oidc.events.collect { event -> if (event != null) stepUp.handle(event) }
        }
    }

    fun open(user: AdminUserDto) {
        _state.update {
            it.copy(user = user, identities = emptyList(), failed = false, confirmUnlink = null)
        }
        load()
    }

    fun close() {
        _state.update { it.copy(user = null, confirmUnlink = null) }
    }

    fun reload() = load()

    private fun load() {
        val user = _state.value.user ?: return
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val envelope = oidc.loadAdminIdentities(user.id).getOrNull()
            _state.update {
                if (it.user?.id != user.id) it
                else if (envelope == null) it.copy(loading = false, failed = true)
                else it.copy(loading = false, identities = envelope.data)
            }
        }
    }

    fun onUnlinkClicked(identity: OidcIdentity) {
        if (identity.canUnlink) _state.update { it.copy(confirmUnlink = identity) }
    }

    fun dismissUnlink() = _state.update { it.copy(confirmUnlink = null) }

    fun confirmUnlink() {
        val identity = _state.value.confirmUnlink ?: return
        val user = _state.value.user ?: return
        _state.update { it.copy(confirmUnlink = null) }
        guard.confirm(R.string.unlock_reason_unlink_login) { beginAdminUnlink(identity, user) }
    }

    private fun beginAdminUnlink(identity: OidcIdentity, user: AdminUserDto) {
        pendingUnlink = identity
        viewModelScope.launch {
            // What the administrator can confirm with is about *their own* account.
            val own = oidc.loadIdentities().getOrNull()
            val provider =
                own?.data?.firstNotNullOfOrNull { mine ->
                    _state.value.providers.firstOrNull { it.id == mine.provider }
                }
            stepUp.begin(
                ReauthRequest(
                    action = ReauthAction.ADMIN_UNLINK_IDENTITY,
                    target = ReauthTarget.AdminIdentity(user.id, identity.id),
                    canUsePassword = own?.meta?.passwordEnabled ?: true,
                    oidcProvider = provider,
                )
            )
        }
    }

    fun dismissSheet() = stepUp.dismiss()

    fun submitPassword(password: String) = stepUp.submitPassword(password)

    fun confirmWithProvider() = stepUp.useProvider()

    /** Confirms with one of the account's passkeys (offered by the sheet when it has one). */
    fun confirmWithPasskey(host: blue.anika.wolle.data.passkey.PasskeyHost) =
        stepUp.usePasskey(host)

    private suspend fun unlinkWith(proof: ReauthProof) {
        val identity = pendingUnlink ?: return
        val user = _state.value.user ?: return
        pendingUnlink = null
        _state.update { it.copy(working = true) }
        val failure = oidc.adminUnlink(user.id, identity.id, proof)
        _state.update { it.copy(working = false) }
        when (failure) {
            null -> {
                feedback.show(R.string.oidc_unlinked_done)
                load()
            }
            OidcFailure.LastLoginMethod ->
                feedback.show(R.string.error_oidc_admin_last_login_method)
            else -> feedback.showOidcFailure(failure, _state.value.providerName(identity.provider))
        }
    }

    private fun providerName(): String =
        _state.value.providers.firstOrNull()?.name ?: DEFAULT_PROVIDER_NAME
}
