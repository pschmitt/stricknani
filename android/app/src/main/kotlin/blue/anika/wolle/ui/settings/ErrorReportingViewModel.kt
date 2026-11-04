package blue.anika.wolle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.errorreporting.ErrorReportingController
import blue.anika.wolle.data.errorreporting.ErrorReportingUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The "Send error reports" switch of Account settings; hidden unless the server offers it. */
@HiltViewModel
class ErrorReportingViewModel
@Inject
constructor(private val controller: ErrorReportingController) : ViewModel() {
    val state: StateFlow<ErrorReportingUiState> =
        controller.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ErrorReportingUiState(),
        )

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** True after the server could not record the last change (this device still obeys it). */
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    init {
        viewModelScope.launch { controller.sync() }
    }

    fun set(enabled: Boolean) {
        if (_busy.value) return
        _busy.value = true
        _saveFailed.value = false
        viewModelScope.launch {
            _saveFailed.value = !controller.setEnabled(enabled)
            _busy.value = false
        }
    }
}
