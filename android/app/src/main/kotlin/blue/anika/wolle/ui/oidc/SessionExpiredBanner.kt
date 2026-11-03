package blue.anika.wolle.ui.oidc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.oidc.AUTH_ORIGIN_OIDC
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcEvent
import blue.anika.wolle.data.oidc.OidcFlowType
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.PhosphorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the "sign in again" card. The card appears only for a signed-in OIDC session that is known
 * to have expired (`401 session_expired`, or past its `expires_at`); nothing is wiped, so the cache
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

    val visible: StateFlow<Boolean> =
        combine(settings.isConfigured, settings.sessionExpired, settings.authInfo) {
                configured,
                expired,
                auth ->
                configured && expired && auth.origin == AUTH_ORIGIN_OIDC
            }
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

/**
 * A gentle card floating above the bottom bar when the OIDC sign-in expired. Draws nothing
 * otherwise, so it is safe to place on every screen.
 */
@Composable
fun SessionExpiredBanner(
    modifier: Modifier = Modifier,
    viewModel: SessionExpiredViewModel = hiltViewModel(),
) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return
    val working by viewModel.working.collectAsStateWithLifecycle()
    val providerName by viewModel.providerName.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth().testTag("session-expired-banner"),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    PhosphorIcons.LockKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.oidc_session_expired_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        stringResource(R.string.oidc_session_expired_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Button(
                        onClick = viewModel::signInAgain,
                        enabled = !working,
                        modifier = Modifier.padding(top = 8.dp).testTag("session-expired-sign-in"),
                    ) {
                        if (working) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp).padding(end = 0.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(
                            stringResource(R.string.oidc_session_expired_button, providerName),
                            modifier = Modifier.padding(start = if (working) 8.dp else 0.dp),
                        )
                    }
                }
            }
        }
    }
}
