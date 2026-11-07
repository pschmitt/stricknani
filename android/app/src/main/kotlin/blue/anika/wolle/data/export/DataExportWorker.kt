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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Downloads the data export in the background so leaving the screen does not stop it. It is a plain
 * worker with its own progress notification, not a foreground service: the app keeps that service
 * (and its Play permission) out of its manifest. WorkManager gives a worker about ten minutes,
 * ample for a personal archive.
 */
@HiltWorker
class DataExportWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: DataExportRepository,
    private val notifier: DataExportNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val target = inputData.getString(KEY_URI)?.toUri() ?: return failed(ExportFailure.Failed)
        val name = inputData.getString(KEY_NAME).orEmpty()
        val format = ExportFormat.fromName(inputData.getString(KEY_FORMAT))
        notifier.progress(0)
        var lastReport = 0L
        val result =
            try {
                repository.download(target, format) { bytes ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= REPORT_INTERVAL_MILLIS) {
                        lastReport = now
                        notifier.progress(bytes)
                        // Fire and forget: progress for the screen is best effort.
                        setProgressAsync(workDataOf(KEY_BYTES to bytes))
                    }
                }
            } catch (e: CancellationException) {
                notifier.cancel()
                throw e
            }
        return when (result) {
            is ExportResult.Success -> {
                notifier.succeeded(target, name, format)
                Result.success()
            }
            is ExportResult.Failure -> failed(result.reason)
        }
    }

    private fun failed(reason: ExportFailure): Result {
        notifier.failed(reason)
        return Result.failure(workDataOf(KEY_FAILURE to reason.name))
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_FORMAT = "format"
        const val KEY_BYTES = "bytes"
        const val KEY_FAILURE = "failure"
        private const val REPORT_INTERVAL_MILLIS = 500L
    }
}

/** Starts the download job and reports its state to the Backups screen. */
@Singleton
class DataExportScheduler @Inject constructor(private val workManager: WorkManager) {

    /** Only one download at a time; a second tap while one runs is ignored. */
    fun start(target: Uri, fileName: String, format: ExportFormat) {
        val request =
            OneTimeWorkRequestBuilder<DataExportWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    workDataOf(
                        DataExportWorker.KEY_URI to target.toString(),
                        DataExportWorker.KEY_NAME to fileName,
                        DataExportWorker.KEY_FORMAT to format.name,
                    )
                )
                .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    val status: Flow<ExportJobStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).map { infos ->
            val info = infos.lastOrNull()
            exportJobStatus(
                info?.state,
                info?.progress?.getLong(DataExportWorker.KEY_BYTES, 0L) ?: 0L,
                info?.outputData?.getString(DataExportWorker.KEY_FAILURE),
            )
        }

    private companion object {
        const val UNIQUE_NAME = "data-export"
    }
}
