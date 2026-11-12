package blue.anika.wolle.ui.oidc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcEvent
import blue.anika.wolle.data.oidc.OidcFlowType
import blue.anika.wolle.data.oidc.OidcIdentity
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.DangerousActionGuard
import blue.anika.wolle.ui.common.MutationFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LinkedLoginsPhase {
    /** Not known yet, or the server lists no provider: the card is not shown at all. */
    data object Hidden : LinkedLoginsPhase

    data object Ready : LinkedLoginsPhase

    data object Failed : LinkedLoginsPhase
}

data class LinkedLoginsState(
    val phase: LinkedLoginsPhase = LinkedLoginsPhase.Hidden,
    val providers: List<OidcProvider> = emptyList(),
    val identities: List<OidcIdentity> = emptyList(),
    val passwordEnabled: Boolean = true,
    /** The identity the user is asked to confirm unlinking. */
    val confirmUnlink: OidcIdentity? = null,
    /** An unlink request is in flight. */
    val working: Boolean = false,
) {
    /** Providers the account can still link (one login per provider). */
    val linkable: List<OidcProvider>
        get() = providers.filter { p -> identities.none { it.provider == p.id } }

    fun providerName(id: String): String =
        providers.firstOrNull { it.id == id }?.name ?: id.replaceFirstChar { it.uppercase() }
}

/** What to do once the confirm step produced a proof. */
private sealed interface AfterProof {
    data class Link(val provider: OidcProvider) : AfterProof

    data class Unlink(val identity: OidcIdentity) : AfterProof
}

/**
 * Settings → Account → Linked logins. Shown only when the server's `GET /api/v2/meta` lists
 * providers; today it lists none, so the card stays hidden.
 */
@HiltViewModel
class LinkedLoginsViewModel
@Inject
constructor(
    private val oidc: OidcCoordinator,
    private val settings: SettingsRepository,
    private val feedback: MutationFeedback,
    private val guard: DangerousActionGuard,
) : ViewModel() {
    private val _state = MutableStateFlow(LinkedLoginsState())
    val state: StateFlow<LinkedLoginsState> = _state.asStateFlow()

    private var afterProof: AfterProof? = null

    private val stepUp =
        StepUp(
            oidc,
            viewModelScope,
            onProof = ::proceed,
            onFailure = { failure -> feedback.showOidcFailure(failure, providerName()) },
        )
    val sheet: StateFlow<ReauthUiState?> = stepUp.sheet

    init {
        load()
        viewModelScope.launch { oidc.events.collect(::onEvent) }
    }

    fun reload() = load()

    private fun load() {
        viewModelScope.launch {
            val providers = oidc.providers(settings.credentials.value.serverUrl)
            if (providers.isEmpty()) {
                _state.update { it.copy(phase = LinkedLoginsPhase.Hidden, providers = emptyList()) }
                return@launch
            }
            val result = oidc.loadIdentities()
            val envelope = result.getOrNull()
            _state.update {
                if (envelope == null)
                    it.copy(phase = LinkedLoginsPhase.Failed, providers = providers)
                else
                    it.copy(
                        phase = LinkedLoginsPhase.Ready,
                        providers = providers,
                        identities = envelope.data,
                        passwordEnabled = envelope.meta.passwordEnabled,
                    )
            }
        }
    }

    // ---- user actions ----------------------------------------------------------------------

    fun onLinkClicked(provider: OidcProvider) {
        afterProof = AfterProof.Link(provider)
        stepUp.begin(request(ReauthAction.LINK_IDENTITY, ReauthTarget.Provider(provider.id)))
    }

    fun onUnlinkClicked(identity: OidcIdentity) {
        if (!identity.canUnlink) return
        _state.update { it.copy(confirmUnlink = identity) }
    }

    fun dismissUnlink() = _state.update { it.copy(confirmUnlink = null) }

    fun confirmUnlink() {
        val identity = _state.value.confirmUnlink ?: return
        _state.update { it.copy(confirmUnlink = null) }
        guard.confirm(R.string.unlock_reason_unlink_login) {
            afterProof = AfterProof.Unlink(identity)
            stepUp.begin(request(ReauthAction.UNLINK_IDENTITY, ReauthTarget.Identity(identity.id)))
        }
    }

    fun dismissSheet() = stepUp.dismiss()

    fun submitPassword(password: String) = stepUp.submitPassword(password)

    fun confirmWithProvider() = stepUp.useProvider()

    // ---- internals -------------------------------------------------------------------------

    private fun request(action: ReauthAction, target: ReauthTarget): ReauthRequest {
        val current = _state.value
        // Provider reauthentication needs an identity the account already has.
        val provider =
            current.identities.firstNotNullOfOrNull { id ->
                current.providers.firstOrNull { it.id == id.provider }
            }
        return ReauthRequest(
            action = action,
            target = target,
            canUsePassword = current.passwordEnabled,
            oidcProvider = provider,
        )
    }

    private suspend fun proceed(proof: ReauthProof) {
        when (val next = afterProof) {
            is AfterProof.Link -> {
                afterProof = null
                // The browser opens through OidcCoordinator.browserRequests; the result arrives as
                // an OidcEvent.Linked.
                oidc.startLink(next.provider.id, proof)?.let {
                    feedback.showOidcFailure(it, next.provider.name)
                }
            }
            is AfterProof.Unlink -> {
                afterProof = null
                _state.update { it.copy(working = true) }
                val failure = oidc.unlink(next.identity.id, proof)
                _state.update { it.copy(working = false) }
                if (failure == null) {
                    feedback.show(R.string.oidc_unlinked_done)
                    // If this was the login the current token came from, the server revoked it;
                    // this very request then returns 401 session_expired, which raises the
                    // "sign in again" card with the offline data intact.
                    load()
                } else {
                    feedback.showOidcFailure(
                        failure,
                        _state.value.providerName(next.identity.provider),
                    )
                }
            }
            null -> Unit
        }
    }

    private suspend fun onEvent(event: OidcEvent?) {
        if (event == null) return
        if (stepUp.handle(event)) return
        when (event) {
            is OidcEvent.Linked -> {
                oidc.consume(event)
                feedback.show(
                    R.string.oidc_linked_done,
                    _state.value.providerName(event.identity.provider),
                )
                load()
            }
            is OidcEvent.Failed ->
                if (event.flow == OidcFlowType.LINK) {
                    oidc.consume(event)
                    feedback.showOidcFailure(event.failure, providerName())
                }
            else -> Unit
        }
    }

    private fun providerName(): String =
        _state.value.providers.firstOrNull()?.name ?: DEFAULT_PROVIDER_NAME
}
