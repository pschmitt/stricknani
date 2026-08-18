package blue.anika.wolle.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.onboarding.OnboardingError
import blue.anika.wolle.data.onboarding.OnboardingValidationException
import blue.anika.wolle.data.onboarding.OnboardingValidator
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun connect(serverUrl: String, apiToken: String) {
        if (serverUrl.isBlank() || apiToken.isBlank()) {
            _uiState.value = OnboardingUiState.Error("Enter both the server URL and the API token")
            return
        }
        _uiState.value = OnboardingUiState.Validating
        viewModelScope.launch {
            validator
                .validate(serverUrl, apiToken)
                .onSuccess {
                    // Only persisted - and only now starts being read by the network layer's
                    // interceptors - once the server has actually confirmed this token works.
                    settingsRepository.save(serverUrl, apiToken)
                    _uiState.value = OnboardingUiState.Success
                }
                .onFailure { error ->
                    _uiState.value = OnboardingUiState.Error(error.toUserMessage())
                }
        }
    }

    private fun Throwable.toUserMessage(): String =
        when (this) {
            is OnboardingValidationException ->
                when (val e = error) {
                    OnboardingError.MalformedUrl ->
                        "Enter a valid server URL, e.g. https://stricknani.example.com"
                    is OnboardingError.NotAStricknaniServer ->
                        "That doesn't look like a Stricknani server (HTTP ${e.code})."
                    OnboardingError.Unauthorized ->
                        "That server rejected the API token. Generate a new one in Stricknani " +
                            "under your account menu → API Tokens and try again."
                    OnboardingError.Unreachable ->
                        "Couldn't reach that server. Check the URL and your network connection."
                    is OnboardingError.ServerError ->
                        "The server responded with an error (HTTP ${e.code})."
                }
            else -> message?.takeIf { it.isNotBlank() } ?: "Couldn't connect to that server."
        }
}
