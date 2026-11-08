package blue.anika.wolle.data.export

import blue.anika.wolle.sync.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The transfer itself for both paths, a worker and a job service: runs the repository, throttles
 * progress, keeps [DataTransferStatusStore] current and posts the final notification. Callers add
 * what only they can do (WorkManager progress data, the job's own notification).
 */
@Singleton
class DataExportTransfer
@Inject
constructor(
    private val repository: DataExportRepository,
    private val notifier: DataExportNotifier,
    private val store: DataTransferStatusStore,
    private val passphrases: PassphraseHolder,
) {
    /** Marks a download as started or queued, before the worker or job begins. */
    fun queued() = store.setExport(ExportJobStatus.Running(0))

    val isRunning: Boolean
        get() = store.export.value is ExportJobStatus.Running

    suspend fun run(request: DataExportRequest, onProgress: (Long) -> Unit): ExportResult {
        store.setExport(ExportJobStatus.Running(0))
        onProgress(0)
        var lastReport = 0L
        // Read once. A passphrase that is gone (the process died since the tap) is a plain failure.
        val id = request.passphraseId
        val passphrase =
            if (id == null) null
            else passphrases.take(id) ?: return ExportResult.Failure(ExportFailure.Failed)
        return repository.download(request.target, request.format, passphrase) { bytes ->
            val now = System.currentTimeMillis()
            if (now - lastReport >= REPORT_INTERVAL_MILLIS) {
                lastReport = now
                store.setExport(ExportJobStatus.Running(bytes))
                onProgress(bytes)
            }
        }
    }

    /** Publishes the outcome: status and notification. */
    fun finish(request: DataExportRequest, result: ExportResult) {
        when (result) {
            is ExportResult.Success -> {
                store.setExport(ExportJobStatus.Succeeded)
                // The file is saved: a notification that cannot be posted must not fail it.
                runCatching {
                    notifier.succeeded(
                        request.target,
                        request.fileName,
                        request.format,
                        request.encrypted,
                        result.bytes,
                    )
                }
            }
            is ExportResult.Failure -> fail(result.reason)
        }
    }

    fun fail(reason: ExportFailure) {
        store.setExport(ExportJobStatus.Failed(reason))
        notifier.failed(reason)
    }

    /** The transfer was cancelled on purpose: no notification, nothing to report. */
    fun cancelled() {
        notifier.cancel()
        store.setExport(ExportJobStatus.Idle)
    }
}

/** The restore counterpart of [DataExportTransfer]. */
@Singleton
class DataRestoreTransfer
@Inject
constructor(
    private val repository: DataRestoreRepository,
    private val notifier: DataRestoreNotifier,
    private val store: DataTransferStatusStore,
    private val syncScheduler: SyncScheduler,
    private val passphrases: PassphraseHolder,
) {
    fun queued(request: DataRestoreRequest) {
        store.setPassphrasePrompt(null)
        store.setRestore(RestoreJobStatus.Running(0, request.totalBytes))
    }

    val isRunning: Boolean
        get() = store.restore.value is RestoreJobStatus.Running

    suspend fun run(request: DataRestoreRequest, onProgress: (sent: Long) -> Unit): RestoreResult {
        val total = request.totalBytes
        store.setRestore(RestoreJobStatus.Running(0, total))
        onProgress(0)
        var lastReport = 0L
        val id = request.passphraseId
        val passphrase =
            if (id == null) null
            else passphrases.take(id) ?: return RestoreResult.Failure(RestoreFailure.Failed)
        return repository.restore(request.source, passphrase) { sent ->
            val now = System.currentTimeMillis()
            if (now - lastReport >= REPORT_INTERVAL_MILLIS || isProcessing(sent, total)) {
                lastReport = now
                store.setRestore(RestoreJobStatus.Running(sent, total))
                onProgress(sent)
            }
        }
    }

    fun finish(request: DataRestoreRequest, result: RestoreResult) {
        when (result) {
            is RestoreResult.Success -> {
                // The restored items have new `updated_at`: a normal sync brings them in.
                syncScheduler.syncNow()
                store.setRestore(RestoreJobStatus.Succeeded(result.summary))
                runCatching { notifier.succeeded(result.summary) }
            }
            is RestoreResult.Failure -> {
                val wrong = result.reason == RestoreFailure.WrongPassphrase
                if (wrong || result.reason == RestoreFailure.PassphraseRequired) {
                    // Ask again on the Backups screen (also reached through the notification).
                    store.setPassphrasePrompt(
                        PassphrasePrompt(request.source, request.totalBytes, wrong)
                    )
                }
                fail(result.reason)
            }
        }
    }

    /** The user answered or dismissed the passphrase question. */
    fun dismissPassphrasePrompt() = store.setPassphrasePrompt(null)

    fun fail(reason: RestoreFailure) {
        store.setRestore(RestoreJobStatus.Failed(reason))
        notifier.failed(reason)
    }

    fun cancelled() {
        notifier.cancel()
        store.setRestore(RestoreJobStatus.Idle)
    }
}
