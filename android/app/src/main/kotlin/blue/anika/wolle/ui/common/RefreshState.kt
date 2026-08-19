package blue.anika.wolle.ui.common

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The user-visible outcome of a foreground refresh. */
sealed interface RefreshState {
    data object Idle : RefreshState

    data object Refreshing : RefreshState

    data class Finished(val changed: Boolean) : RefreshState

    data object Offline : RefreshState

    data object Error : RefreshState
}

/**
 * Runs one refresh at a time and turns its result into a state the UI can explain to the user. The
 * atomic guard is intentional: a pull gesture and another refresh trigger can arrive before the
 * first coroutine gets its first dispatch, so checking a StateFlow alone is not sufficient.
 */
class RefreshController(private val scope: CoroutineScope) {
    private val refreshInProgress = AtomicBoolean(false)
    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    /** Returns false when another refresh is already running. */
    fun refresh(block: suspend () -> Boolean): Boolean {
        if (!refreshInProgress.compareAndSet(false, true)) return false

        _state.value = RefreshState.Refreshing
        scope.launch {
            try {
                _state.value = RefreshState.Finished(changed = block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _state.value =
                    if (failure.causedByIoFailure()) RefreshState.Offline else RefreshState.Error
            } finally {
                refreshInProgress.set(false)
            }
        }
        return true
    }

    fun clearFeedback() {
        if (_state.value !is RefreshState.Refreshing) {
            _state.value = RefreshState.Idle
        }
    }
}

private fun Throwable.causedByIoFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return true
        current = current.cause
    }
    return false
}
