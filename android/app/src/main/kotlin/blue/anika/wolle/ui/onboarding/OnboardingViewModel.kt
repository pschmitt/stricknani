package blue.anika.wolle.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.account.DeviceTokenRenamer
import blue.anika.wolle.data.device.DeviceNameProvider
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcEvent
import blue.anika.wolle.data.oidc.OidcFlowType
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.data.onboarding.OnboardingError
import blue.anika.wolle.data.onboarding.OnboardingValidationException
import blue.anika.wolle.data.onboarding.OnboardingValidator
import blue.anika.wolle.data.onboarding.PasswordTokenMinter
import blue.anika.wolle.data.onboarding.QrConfigCodec
import blue.anika.wolle.data.onboarding.RegistrationClient
import blue.anika.wolle.data.onboarding.RegistrationError
import blue.anika.wolle.data.onboarding.RegistrationException
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.oidc.DEFAULT_PROVIDER_NAME
import blue.anika.wolle.ui.oidc.message
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface OnboardingUiState {
    data object Idle : OnboardingUiState

    data object Validating : OnboardingUiState

    data class Error(val message: String) : OnboardingUiState

    data object Success : OnboardingUiState
}

@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
    private val validator: OnboardingValidator,
    private val passwordTokenMinter: PasswordTokenMinter,
    private val registrationClient: RegistrationClient,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val oidc: OidcCoordinator,
    private val deviceNames: DeviceNameProvider,
    private val tokenRenamer: DeviceTokenRenamer,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val serverUrlInput = MutableStateFlow("")
    private val _oidcProviders = MutableStateFlow<List<OidcProvider>>(emptyList())

    /**
     * Sign-in providers the entered server lists in `GET /api/v2/meta`. Empty (so the button stays
     * hidden) until a reachable server says otherwise; today's servers list none.
     */
    val oidcProviders: StateFlow<List<OidcProvider>> = _oidcProviders.asStateFlow()

    private val _registrationOpen = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the entered server allows self-service registration, from `registration_enabled` in
     * its meta. Null until a reachable server says (or when it cannot be told), in which case the
     * screen shows neither the "create an account" path nor the "set up your own" card.
     */
    val registrationOpen: StateFlow<Boolean?> = _registrationOpen.asStateFlow()

    private var oidcProviderName = DEFAULT_PROVIDER_NAME

    init {
        viewModelScope.launch { observeServerUrl() }
        viewModelScope.launch { observeOidcResults() }
        viewModelScope.launch {
            oidc.busy.collect { busy ->
                if (busy) _uiState.value = OnboardingUiState.Validating
            }
        }
    }

    /** Called as the server URL field changes; looks for OIDC providers once typing settles. */
    fun onServerUrlChanged(url: String) {
        serverUrlInput.value = url.trim()
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private suspend fun observeServerUrl() {
        serverUrlInput.debounce(SERVER_URL_SETTLE_MILLIS).distinctUntilChanged().collectLatest { url
            ->
            val valid = url.trimEnd('/').toHttpUrlOrNull() != null
            _oidcProviders.value = if (valid) oidc.providers(url) else emptyList()
            _registrationOpen.value = if (valid) registrationClient.isOpen(url) else null
        }
    }

    private suspend fun observeOidcResults() {
        oidc.events.collect { event ->
            when (event) {
                is OidcEvent.SignedIn -> oidc.consume(event)
                is OidcEvent.Failed ->
                    if (event.flow == OidcFlowType.LOGIN) {
                        _uiState.value =
                            OnboardingUiState.Error(
                                event.failure.message(context, oidcProviderName)
                            )
                        oidc.consume(event)
                    }
                else -> Unit
            }
        }
    }

    /** Starts the provider sign-in; the browser opens through [OidcCoordinator.browserRequests]. */
    fun signInWithOidc(serverUrl: String, provider: OidcProvider) {
        if (serverUrl.isBlank()) {
            _uiState.value =
                OnboardingUiState.Error(context.getString(R.string.onboarding_error_missing_url))
            return
        }
        oidcProviderName = provider.name
        _uiState.value = OnboardingUiState.Validating
        viewModelScope.launch {
            val failure = oidc.startLogin(serverUrl.trim().trimEnd('/'), provider.id)
            _uiState.value =
                if (failure == null) OnboardingUiState.Idle
                else OnboardingUiState.Error(failure.message(context, provider.name))
        }
    }

    fun connect(serverUrl: String, apiToken: String, renameAfterConnect: Boolean = false) {
        if (serverUrl.isBlank() || apiToken.isBlank()) {
            _uiState.value =
                OnboardingUiState.Error(
                    context.getString(R.string.onboarding_error_missing_url_token)
                )
            return
        }
        _uiState.value = OnboardingUiState.Validating
        viewModelScope.launch {
            validator
                .validate(serverUrl, apiToken)
                .onSuccess {
                    persistAndSucceed(serverUrl, apiToken)
                    if (renameAfterConnect) tokenRenamer.renameAfterSetup()
                }
                .onFailure { error ->
                    _uiState.value = OnboardingUiState.Error(error.toUserMessage())
                }
        }
    }

    /**
     * Password-login onboarding (SNA-13): mints a fresh PAT from an email/password instead of
     * requiring the user to first visit the web Settings page. The mint itself already proves the
     * token works (the server just created and returned it), so this skips [OnboardingValidator]'s
     * separate confirmation round-trip - unlike [connect], which validates a token it didn't just
     * mint.
     */
    fun signInWithPassword(serverUrl: String, email: String, password: String) {
        if (serverUrl.isBlank() || email.isBlank() || password.isBlank()) {
            _uiState.value =
                OnboardingUiState.Error(
                    context.getString(R.string.onboarding_error_missing_password_fields)
                )
            return
        }
        _uiState.value = OnboardingUiState.Validating
        viewModelScope.launch {
            passwordTokenMinter
                .mintToken(
                    serverUrl,
                    email,
                    password,
                    tokenName = deviceNames.tokenName(),
                    deviceName = deviceNames.deviceName(),
                )
                .onSuccess { apiToken ->
                    persistAndSucceed(serverUrl, apiToken)
                    settingsRepository.setAccountEmail(email)
                }
                .onFailure { error ->
                    _uiState.value = OnboardingUiState.Error(error.toUserMessage())
                }
        }
    }

    /**
     * Self-service registration: creates a member account and signs in with the token the server
     * returns, exactly like [signInWithPassword] does after a login. Only reachable when
     * [registrationOpen] is true.
     */
    fun register(serverUrl: String, email: String, password: String, confirmation: String) {
        if (serverUrl.isBlank() || email.isBlank() || password.isBlank()) {
            _uiState.value =
                OnboardingUiState.Error(
                    context.getString(R.string.onboarding_error_missing_password_fields)
                )
            return
        }
        if (password != confirmation) {
            _uiState.value =
                OnboardingUiState.Error(
                    context.getString(R.string.onboarding_register_error_mismatch)
                )
            return
        }
        _uiState.value = OnboardingUiState.Validating
        viewModelScope.launch {
            registrationClient
                .register(serverUrl, email, password, deviceName = deviceNames.deviceName())
                .onSuccess { apiToken ->
                    persistAndSucceed(serverUrl, apiToken)
                    settingsRepository.setAccountEmail(email.trim().lowercase())
                }
                .onFailure { error ->
                    _uiState.value = OnboardingUiState.Error(error.toRegistrationMessage())
                }
        }
    }

    /**
     * QR-code onboarding (SNA-13): decodes a payload scanned from the web Settings page's setup QR,
     * then goes through the normal [connect] validation - unlike [signInWithPassword]'s freshly
     * minted token, a QR code can sit around (screenshotted, scanned later) so its token could be
     * stale or already revoked by the time it's actually scanned.
     */
    fun connectFromScannedText(scannedText: String) {
        val payload = QrConfigCodec.decode(scannedText)
        if (payload == null) {
            _uiState.value =
                OnboardingUiState.Error(context.getString(R.string.onboarding_error_invalid_qr))
            return
        }
        connect(payload.baseUrl, payload.token, renameAfterConnect = true)
    }

    private fun persistAndSucceed(serverUrl: String, apiToken: String) {
        // Only persisted - and only now starts being read by the network layer's interceptors -
        // once the server has actually confirmed this token works.
        settingsRepository.save(serverUrl, apiToken)
        // So the first sync doesn't wait for the periodic schedule.
        syncScheduler.syncNow()
        _uiState.value = OnboardingUiState.Success
    }

    private companion object {
        const val SERVER_URL_SETTLE_MILLIS = 600L
    }

    private fun Throwable.toRegistrationMessage(): String {
        val error = (this as? RegistrationException)?.error ?: return toUserMessage()
        return when (error) {
            RegistrationError.Closed -> context.getString(R.string.onboarding_register_error_closed)
            RegistrationError.Invalid ->
                context.getString(R.string.onboarding_register_error_invalid)
            RegistrationError.NotCreated ->
                context.getString(R.string.onboarding_register_error_not_created)
            RegistrationError.RateLimited ->
                context.getString(R.string.onboarding_error_rate_limited)
            RegistrationError.Unreachable ->
                context.getString(R.string.onboarding_error_unreachable)
            is RegistrationError.ServerError ->
                context.getString(R.string.onboarding_error_server_error, error.code)
        }
    }

    private fun Throwable.toUserMessage(): String =
        when (this) {
            is OnboardingValidationException ->
                when (val e = error) {
                    OnboardingError.MalformedUrl ->
                        context.getString(R.string.onboarding_error_malformed_url)
                    is OnboardingError.NotAStricknaniServer ->
                        context.getString(R.string.onboarding_error_not_a_server, e.code)
                    OnboardingError.Unauthorized ->
                        context.getString(R.string.onboarding_error_unauthorized)
                    OnboardingError.InvalidCredentials ->
                        context.getString(R.string.onboarding_error_invalid_credentials)
                    OnboardingError.RateLimited ->
                        context.getString(R.string.onboarding_error_rate_limited)
                    OnboardingError.Unreachable ->
                        context.getString(R.string.onboarding_error_unreachable)
                    is OnboardingError.ServerError ->
                        context.getString(R.string.onboarding_error_server_error, e.code)
                }
            else ->
                message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.onboarding_error_generic)
        }
}
