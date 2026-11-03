package blue.anika.wolle.ui.oidc

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.oidc.AUTH_ORIGIN_OIDC
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcEvent
import blue.anika.wolle.data.oidc.OidcFlowType
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.MutationFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the "sign in again" card. [ended] is true for any signed-in session whose token the server
 * rejected (`401 session_expired`, or an OIDC token past its `expires_at`); [visible] is the same
 * for an OIDC session, which signs in again through the identity provider right here. Other
 * sessions (password, QR setup) sign in again on their own screen. Nothing is wiped, so the cache
 * and queued writes wait for the new sign-in, which then restarts the sync.
 */
@HiltViewModel
class SessionExpiredViewModel
@Inject
constructor(
    private val settings: SettingsRepository,
    private val oidc: OidcCoordinator,
    private val feedback: MutationFeedback,
) : ViewModel() {

    val ended: StateFlow<Boolean> =
        combine(settings.isConfigured, settings.sessionExpired) { configured, expired ->
                configured && expired
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the session came from the identity provider (and so signs in again with it). */
    val isOidc: StateFlow<Boolean> =
        settings.authInfo
            .map { it.origin == AUTH_ORIGIN_OIDC }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val visible: StateFlow<Boolean> =
        combine(ended, isOidc) { ended, oidc -> ended && oidc }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _starting = MutableStateFlow(false)
    val working: StateFlow<Boolean> =
        combine(_starting, oidc.busy) { starting, busy -> starting || busy }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _providerName = MutableStateFlow(DEFAULT_PROVIDER_NAME)
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    init {
        // Know the provider's name before the button is pressed, so the card names it right.
        viewModelScope.launch {
            visible.collect { shown ->
                if (shown) {
                    oidc.providers(settings.credentials.value.serverUrl).firstOrNull()?.let {
                        _providerName.value = it.name
                    }
                }
            }
        }
        viewModelScope.launch {
            oidc.events.collect { event ->
                when (event) {
                    is OidcEvent.SignedIn -> if (event.reSignIn) oidc.consume(event)
                    is OidcEvent.Failed ->
                        if (event.flow == OidcFlowType.LOGIN && settings.isConfigured.value) {
                            feedback.showOidcFailure(event.failure, _providerName.value)
                            oidc.consume(event)
                        }
                    else -> Unit
                }
            }
        }
    }

    /** Re-runs the provider login against the saved server. */
    fun signInAgain() {
        if (_starting.value) return
        _starting.value = true
        viewModelScope.launch {
            try {
                val serverUrl = settings.credentials.value.serverUrl
                val provider = oidc.providers(serverUrl).firstOrNull()
                if (provider == null) {
                    feedback.show(R.string.oidc_session_expired_unavailable, _providerName.value)
                    return@launch
                }
                _providerName.value = provider.name
                oidc.startLogin(serverUrl, provider.id)?.let {
                    feedback.showOidcFailure(it, provider.name)
                }
            } finally {
                _starting.value = false
            }
        }
    }
}
