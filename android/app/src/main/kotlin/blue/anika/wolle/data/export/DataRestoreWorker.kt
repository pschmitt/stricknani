package blue.anika.wolle.data.export

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import blue.anika.wolle.sync.SyncScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Uploads a Stricknani download and restores it on the server, in the background so leaving the
 * screen does not stop it. A plain worker with its own notification, like [DataExportWorker].
 */
@HiltWorker
class DataRestoreWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: DataRestoreRepository,
    private val notifier: DataRestoreNotifier,
    private val syncScheduler: SyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_URI)?.toUri() ?: return failed(RestoreFailure.Failed)
        val total = inputData.getLong(KEY_TOTAL, 0L)
        notifier.progress(uploadPercent(0, total))
        var lastReport = 0L
        val result =
            try {
                repository.restore(source) { sent ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= REPORT_INTERVAL_MILLIS || isProcessing(sent, total)) {
                        lastReport = now
                        notifier.progress(uploadPercent(sent, total))
                        setProgressAsync(workDataOf(KEY_SENT to sent, KEY_TOTAL to total))
                    }
                }
            } catch (e: CancellationException) {
                notifier.cancel()
                throw e
            }
        return when (result) {
            is RestoreResult.Success -> {
                // The restored items have new `updated_at`: a normal sync brings them in.
                syncScheduler.syncNow()
                notifier.succeeded(result.summary)
                Result.success(
                    workDataOf(
                        KEY_PROJECTS to result.summary.projects,
                        KEY_YARNS to result.summary.yarns,
                        KEY_MEDIA to result.summary.mediaFiles,
                        KEY_SKIPPED to result.summary.mediaSkipped,
                    )
                )
            }
            is RestoreResult.Failure -> failed(result.reason)
        }
    }

    private fun failed(reason: RestoreFailure): Result {
        notifier.failed(reason)
        return Result.failure(workDataOf(KEY_FAILURE to reason.name))
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_TOTAL = "total"
        const val KEY_SENT = "sent"
        const val KEY_FAILURE = "failure"
        const val KEY_PROJECTS = "projects"
        const val KEY_YARNS = "yarns"
        const val KEY_MEDIA = "media"
        const val KEY_SKIPPED = "skipped"
        private const val REPORT_INTERVAL_MILLIS = 500L
    }
}

/** Starts the restore job and reports its state to the Backups screen. */
@Singleton
class DataRestoreScheduler
@Inject
constructor(private val workManager: WorkManager, private val repository: DataRestoreRepository) {

    /** Only one restore at a time; a second tap while one runs is ignored. */
    fun start(source: Uri) {
        val request =
            OneTimeWorkRequestBuilder<DataRestoreWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    workDataOf(
                        DataRestoreWorker.KEY_URI to source.toString(),
                        DataRestoreWorker.KEY_TOTAL to repository.sizeOf(source),
                    )
                )
                .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    val status: Flow<RestoreJobStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).map { infos ->
            val info = infos.lastOrNull()
            val out = info?.outputData
            restoreJobStatus(
                info?.state,
                info?.progress?.getLong(DataRestoreWorker.KEY_SENT, 0L) ?: 0L,
                info?.progress?.getLong(DataRestoreWorker.KEY_TOTAL, 0L) ?: 0L,
                out?.getString(DataRestoreWorker.KEY_FAILURE),
                RestoreSummary(
                    projects = out?.getInt(DataRestoreWorker.KEY_PROJECTS, 0) ?: 0,
                    yarns = out?.getInt(DataRestoreWorker.KEY_YARNS, 0) ?: 0,
                    mediaFiles = out?.getInt(DataRestoreWorker.KEY_MEDIA, 0) ?: 0,
                    mediaSkipped = out?.getInt(DataRestoreWorker.KEY_SKIPPED, 0) ?: 0,
                ),
            )
        }

    private companion object {
        const val UNIQUE_NAME = "data-restore"
    }
}
