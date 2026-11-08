package blue.anika.wolle.data.export

import android.content.Context
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
import blue.anika.wolle.di.SDK_INT_NAME
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Downloads the data export on API < 34 (newer devices run it as a user-initiated job, see
 * [DataExportJobService]). A plain worker with its own progress notification, not a foreground
 * service: the app keeps that service (and its Play permission) out of its manifest. WorkManager
 * gives a worker about ten minutes. The transfer code is shared with the job in
 * [DataExportTransfer].
 */
@HiltWorker
class DataExportWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transfer: DataExportTransfer,
    private val notifier: DataExportNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val target = inputData.getString(KEY_URI)?.toUri() ?: return failed(ExportFailure.Failed)
        val request =
            DataExportRequest(
                target = target,
                fileName = inputData.getString(KEY_NAME).orEmpty(),
                format = ExportFormat.fromName(inputData.getString(KEY_FORMAT)),
                // Only the id of an in-memory passphrase is stored, never the passphrase.
                passphraseId = inputData.getString(KEY_PASSPHRASE_ID),
            )
        val result =
            try {
                transfer.run(request) { bytes ->
                    notifier.progress(bytes)
                    // Fire and forget: progress for the screen is best effort.
                    setProgressAsync(workDataOf(KEY_BYTES to bytes))
                }
            } catch (e: CancellationException) {
                transfer.cancelled()
                throw e
            }
        transfer.finish(request, result)
        return when (result) {
            is ExportResult.Success -> Result.success()
            is ExportResult.Failure -> Result.failure(workDataOf(KEY_FAILURE to result.reason.name))
        }
    }

    private fun failed(reason: ExportFailure): Result {
        transfer.fail(reason)
        return Result.failure(workDataOf(KEY_FAILURE to reason.name))
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_FORMAT = "format"
        const val KEY_PASSPHRASE_ID = "passphrase_id"
        const val KEY_BYTES = "bytes"
        const val KEY_FAILURE = "failure"
    }
}

/**
 * Starts the download on the path the API level allows ([transferPathFor]) and reports its state to
 * the Backups screen from [DataTransferStatusStore].
 */
@Singleton
class DataExportScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val jobs: UserInitiatedJobs,
    private val transfer: DataExportTransfer,
    private val passphrases: PassphraseHolder,
    store: DataTransferStatusStore,
    @Named(SDK_INT_NAME) private val sdkInt: Int,
) {

    /**
     * Only one download at a time; a second tap while one runs is ignored. A [passphrase] makes the
     * download encrypted; it is parked in memory ([PassphraseHolder]) and the transfer gets its id.
     */
    fun start(request: DataExportRequest, passphrase: String? = null) {
        if (transfer.isRunning) return
        transfer.queued()
        val withPassphrase =
            if (passphrase == null) request
            else request.copy(passphraseId = passphrases.put(passphrase))
        startTransfer(
            sdkInt,
            viaJob = { jobs.scheduleExport(withPassphrase) },
            viaWork = { enqueueWorker(withPassphrase) },
        )
    }

    private fun enqueueWorker(request: DataExportRequest) {
        val work =
            OneTimeWorkRequestBuilder<DataExportWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    workDataOf(
                        DataExportWorker.KEY_URI to request.target.toString(),
                        DataExportWorker.KEY_NAME to request.fileName,
                        DataExportWorker.KEY_FORMAT to request.format.name,
                        DataExportWorker.KEY_PASSPHRASE_ID to request.passphraseId,
                    )
                )
                .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, work)
    }

    private val workStatus: Flow<ExportJobStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).map { infos ->
            val info = infos.lastOrNull()
            exportJobStatus(
                info?.state,
                info?.progress?.getLong(DataExportWorker.KEY_BYTES, 0L) ?: 0L,
                info?.outputData?.getString(DataExportWorker.KEY_FAILURE),
            )
        }

    val status: Flow<ExportJobStatus> =
        combine(store.export, workStatus, ::mergeExportStatus).distinctUntilChanged()

    private companion object {
        const val UNIQUE_NAME = "data-export"
    }
}
