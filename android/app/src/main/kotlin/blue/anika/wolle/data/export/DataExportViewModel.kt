package blue.anika.wolle.data.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Backups screen needs for "Your data": the job state and a one-shot outcome. */
@HiltViewModel
class DataExportViewModel @Inject constructor(private val scheduler: DataExportScheduler) :
    ViewModel() {

    val status: StateFlow<ExportJobStatus> =
        scheduler.status.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ExportJobStatus.Idle,
        )

    private val _lastFailure = MutableStateFlow<ExportFailure?>(null)

    /** The failure of the download this screen saw end, until a new download starts. */
    val lastFailure: StateFlow<ExportFailure?> = _lastFailure.asStateFlow()

    private val _saved = Channel<Unit>(Channel.BUFFERED)

    /** Emits once when a download that was running while this screen was open succeeded. */
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    private var wasRunning = false

    init {
        viewModelScope.launch {
            scheduler.status.collect { state ->
                when {
                    state is ExportJobStatus.Running -> {
                        wasRunning = true
                        _lastFailure.value = null
                    }
                    // A finished job left over from an earlier session is not news.
                    wasRunning && state is ExportJobStatus.Failed -> {
                        wasRunning = false
                        _lastFailure.value = state.reason
                    }
                    wasRunning && state is ExportJobStatus.Succeeded -> {
                        wasRunning = false
                        _saved.trySend(Unit)
                    }
                    else -> wasRunning = false
                }
            }
        }
    }

    fun suggestedName(format: ExportFormat, encrypted: Boolean = false): String =
        exportFileName(LocalDate.now(ZoneOffset.UTC), format, encrypted)

    /** With a [passphrase] the download is encrypted; the screen clears its fields after this. */
    fun download(
        target: Uri,
        displayName: String?,
        format: ExportFormat,
        passphrase: String? = null,
    ) {
        _lastFailure.value = null
        scheduler.start(
            DataExportRequest(
                target,
                displayName ?: suggestedName(format, passphrase != null),
                format,
            ),
            passphrase,
        )
    }
}
