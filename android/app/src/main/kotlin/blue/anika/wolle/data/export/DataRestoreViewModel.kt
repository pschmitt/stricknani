package blue.anika.wolle.data.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Backups screen needs for "Restore from a file". */
@HiltViewModel
class DataRestoreViewModel @Inject constructor(private val scheduler: DataRestoreScheduler) :
    ViewModel() {

    val status: StateFlow<RestoreJobStatus> =
        scheduler.status.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            RestoreJobStatus.Idle,
        )

    private val _lastFailure = MutableStateFlow<RestoreFailure?>(null)

    /** The failure of the restore this screen saw end, until a new restore starts. */
    val lastFailure: StateFlow<RestoreFailure?> = _lastFailure.asStateFlow()

    private val _lastSummary = MutableStateFlow<RestoreSummary?>(null)

    /** What the restore this screen saw finish added, until a new restore starts. */
    val lastSummary: StateFlow<RestoreSummary?> = _lastSummary.asStateFlow()

    private var wasRunning = false

    init {
        viewModelScope.launch {
            scheduler.status.collect { state ->
                when {
                    state is RestoreJobStatus.Running -> {
                        wasRunning = true
                        _lastFailure.value = null
                        _lastSummary.value = null
                    }
                    // A finished job left over from an earlier session is not news.
                    wasRunning && state is RestoreJobStatus.Failed -> {
                        wasRunning = false
                        _lastFailure.value = state.reason
                    }
                    wasRunning && state is RestoreJobStatus.Succeeded -> {
                        wasRunning = false
                        _lastSummary.value = state.summary
                    }
                    else -> wasRunning = false
                }
            }
        }
    }

    fun restore(source: Uri) {
        _lastFailure.value = null
        _lastSummary.value = null
        scheduler.start(source)
    }
}
