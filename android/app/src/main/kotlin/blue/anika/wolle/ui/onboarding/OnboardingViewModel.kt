package blue.anika.wolle.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.onboarding.OnboardingError
import blue.anika.wolle.data.onboarding.OnboardingValidationException
import blue.anika.wolle.data.onboarding.OnboardingValidator
import blue.anika.wolle.data.onboarding.PasswordTokenMinter
import blue.anika.wolle.data.onboarding.QrConfigCodec
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun connect(serverUrl: String, apiToken: String) {
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
                .onSuccess { persistAndSucceed(serverUrl, apiToken) }
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
                .mintToken(serverUrl, email, password, tokenName = "Android (password login)")
                .onSuccess { apiToken -> persistAndSucceed(serverUrl, apiToken) }
                .onFailure { error ->
                    _uiState.value = OnboardingUiState.Error(error.toUserMessage())
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
        connect(payload.baseUrl, payload.token)
    }

    private fun persistAndSucceed(serverUrl: String, apiToken: String) {
        // Only persisted - and only now starts being read by the network layer's interceptors -
        // once the server has actually confirmed this token works.
        settingsRepository.save(serverUrl, apiToken)
        // So the first sync doesn't wait for the periodic schedule.
        syncScheduler.syncNow()
        _uiState.value = OnboardingUiState.Success
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
