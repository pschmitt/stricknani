package blue.anika.wolle.data.export

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How a user-started download or restore runs. WorkManager gives a worker about ten minutes; a
 * user-initiated data transfer job (API 34+, `JobInfo.Builder.setUserInitiated`) has no such limit
 * and shows up in the system's Task Manager. The app has no foreground service, so a job is the
 * only way past the limit.
 */
enum class TransferPath {
    /** A plain WorkManager worker (API < 34). */
    WorkManager,

    /** A user-initiated data transfer job (API 34+). */
    UserInitiatedJob,
}

/** First API level with user-initiated data transfer jobs (Android 14). */
const val USER_INITIATED_JOBS_API = 34

/** The path for a device at [sdkInt]; the SDK int is injected so tests can pick any level. */
fun transferPathFor(sdkInt: Int): TransferPath =
    if (sdkInt >= USER_INITIATED_JOBS_API) TransferPath.UserInitiatedJob
    else TransferPath.WorkManager

/**
 * Starts a transfer on the path [transferPathFor] picks for [sdkInt]. [viaJob] returns whether the
 * system accepted the job; when it did not, the transfer falls back to the worker so a tap never
 * does nothing. Returns the path that actually runs.
 */
fun startTransfer(sdkInt: Int, viaJob: () -> Boolean, viaWork: () -> Unit): TransferPath {
    if (transferPathFor(sdkInt) == TransferPath.UserInitiatedJob && viaJob()) {
        return TransferPath.UserInitiatedJob
    }
    viaWork()
    return TransferPath.WorkManager
}

/**
 * The state of the download and the restore, shared by both paths. The workers and the job services
 * publish here, the Backups screen observes it (through the schedulers), so the screen does not
 * care which path runs. Lives in memory: a job that survives the process starts again and publishes
 * again.
 */
@Singleton
class DataTransferStatusStore @Inject constructor() {
    private val _export = MutableStateFlow<ExportJobStatus>(ExportJobStatus.Idle)
    val export: StateFlow<ExportJobStatus> = _export.asStateFlow()

    private val _restore = MutableStateFlow<RestoreJobStatus>(RestoreJobStatus.Idle)
    val restore: StateFlow<RestoreJobStatus> = _restore.asStateFlow()

    private val _passphrasePrompt = MutableStateFlow<PassphrasePrompt?>(null)

    /** Set when a restore needs a passphrase; the Backups screen asks for it. */
    val passphrasePrompt: StateFlow<PassphrasePrompt?> = _passphrasePrompt.asStateFlow()

    fun setPassphrasePrompt(prompt: PassphrasePrompt?) {
        _passphrasePrompt.value = prompt
    }

    fun setExport(status: ExportJobStatus) {
        _export.value = status
    }

    fun setRestore(status: RestoreJobStatus) {
        _restore.value = status
    }
}

/**
 * The store wins while it knows something; an idle store falls back to what WorkManager remembers,
 * so a finished worker from an earlier process still shows up (and a job never does).
 */
fun mergeExportStatus(store: ExportJobStatus, work: ExportJobStatus): ExportJobStatus =
    if (store is ExportJobStatus.Idle) work else store

fun mergeRestoreStatus(store: RestoreJobStatus, work: RestoreJobStatus): RestoreJobStatus =
    if (store is RestoreJobStatus.Idle) work else store

/**
 * Whether a stopped job was ended on purpose (the user in the Task Manager, or the app): that is
 * silent, like a cancelled worker. Any other reason (network lost, quota, device state) is a failed
 * transfer the user must hear about. The values are `JobParameters.STOP_REASON_*`.
 */
fun stopWasDeliberate(stopReason: Int): Boolean =
    stopReason == STOP_REASON_CANCELLED_BY_APP || stopReason == STOP_REASON_USER

internal const val STOP_REASON_CANCELLED_BY_APP = 1
internal const val STOP_REASON_USER = 13

/**
 * What a download needs; the parameter object keeps new options out of every signature.
 * [passphraseId] names a passphrase in [PassphraseHolder] (never the passphrase itself, a request
 * is written to disk): when set the download is encrypted.
 */
data class DataExportRequest(
    val target: Uri,
    val fileName: String,
    val format: ExportFormat = ExportFormat.TarGz,
    val passphraseId: String? = null,
) {
    val encrypted: Boolean
        get() = passphraseId != null
}

/**
 * What a restore needs: the archive and its size (0 when unknown). [passphraseId] names the
 * passphrase of an encrypted file in [PassphraseHolder], like for [DataExportRequest].
 */
data class DataRestoreRequest(
    val source: Uri,
    val totalBytes: Long = 0L,
    val passphraseId: String? = null,
)

/** Progress callbacks are throttled to this interval. */
internal const val REPORT_INTERVAL_MILLIS = 500L

/** A running notification update must not outgrow what the job told the system it would move. */
internal const val ESTIMATE_STEP_BYTES = 64L * 1024 * 1024

/** Bytes to tell the system about when the transfer outgrew its [estimate]. */
fun grownEstimate(transferred: Long, estimate: Long): Long =
    if (estimate > 0 && transferred <= estimate) estimate else transferred + ESTIMATE_STEP_BYTES
