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
 * Uploads a Stricknani download and restores it on the server on API < 34 (newer devices run it as
 * a user-initiated job, see [DataRestoreJobService]). A plain worker with its own notification,
 * like [DataExportWorker]; the transfer code is shared in [DataRestoreTransfer].
 */
@HiltWorker
class DataRestoreWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transfer: DataRestoreTransfer,
    private val notifier: DataRestoreNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_URI)?.toUri() ?: return failed(RestoreFailure.Failed)
        val request =
            DataRestoreRequest(
                source,
                inputData.getLong(KEY_TOTAL, 0L),
                // Only the id of an in-memory passphrase is stored, never the passphrase.
                inputData.getString(KEY_PASSPHRASE_ID),
            )
        val total = request.totalBytes
        val result =
            try {
                transfer.run(request) { sent ->
                    notifier.progress(uploadPercent(sent, total))
                    setProgressAsync(workDataOf(KEY_SENT to sent, KEY_TOTAL to total))
                }
            } catch (e: CancellationException) {
                transfer.cancelled()
                throw e
            }
        transfer.finish(request, result)
        return when (result) {
            is RestoreResult.Success ->
                Result.success(
                    workDataOf(
                        KEY_PROJECTS to result.summary.projects,
                        KEY_YARNS to result.summary.yarns,
                        KEY_MEDIA to result.summary.mediaFiles,
                        KEY_SKIPPED to result.summary.mediaSkipped,
                    )
                )
            is RestoreResult.Failure ->
                Result.failure(workDataOf(KEY_FAILURE to result.reason.name))
        }
    }

    private fun failed(reason: RestoreFailure): Result {
        transfer.fail(reason)
        return Result.failure(workDataOf(KEY_FAILURE to reason.name))
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_TOTAL = "total"
        const val KEY_SENT = "sent"
        const val KEY_PASSPHRASE_ID = "passphrase_id"
        const val KEY_FAILURE = "failure"
        const val KEY_PROJECTS = "projects"
        const val KEY_YARNS = "yarns"
        const val KEY_MEDIA = "media"
        const val KEY_SKIPPED = "skipped"
    }
}

/** Starts the restore on the path the API level allows and reports its state to the screen. */
@Singleton
class DataRestoreScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val repository: DataRestoreRepository,
    private val jobs: UserInitiatedJobs,
    private val transfer: DataRestoreTransfer,
    private val passphrases: PassphraseHolder,
    store: DataTransferStatusStore,
    @Named(SDK_INT_NAME) private val sdkInt: Int,
) {

    /**
     * Only one restore at a time; a second tap while one runs is ignored. A [passphrase] (for an
     * encrypted file) is parked in memory ([PassphraseHolder]) and the transfer gets its id.
     */
    fun start(source: Uri, passphrase: String? = null) {
        if (transfer.isRunning) return
        val request =
            DataRestoreRequest(
                source,
                repository.sizeOf(source),
                passphrase?.let(passphrases::put),
            )
        transfer.queued(request)
        startTransfer(
            sdkInt,
            viaJob = { jobs.scheduleRestore(request) },
            viaWork = { enqueueWorker(request) },
        )
    }

    private fun enqueueWorker(request: DataRestoreRequest) {
        val work =
            OneTimeWorkRequestBuilder<DataRestoreWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    workDataOf(
                        DataRestoreWorker.KEY_URI to request.source.toString(),
                        DataRestoreWorker.KEY_TOTAL to request.totalBytes,
                        DataRestoreWorker.KEY_PASSPHRASE_ID to request.passphraseId,
                    )
                )
                .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, work)
    }

    private val workStatus: Flow<RestoreJobStatus> =
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

    /** Set when a restore needs a passphrase (see [DataRestoreTransfer.finish]). */
    val passphrasePrompt: Flow<PassphrasePrompt?> = store.passphrasePrompt

    fun dismissPassphrasePrompt() = transfer.dismissPassphrasePrompt()

    val status: Flow<RestoreJobStatus> =
        combine(store.restore, workStatus, ::mergeRestoreStatus).distinctUntilChanged()

    private companion object {
        const val UNIQUE_NAME = "data-restore"
    }
}
