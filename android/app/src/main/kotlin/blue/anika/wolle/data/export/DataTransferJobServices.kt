package blue.anika.wolle.data.export

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs a transfer as a user-initiated data transfer job (API 34+). The system needs a notification
 * for the job right away ([JobService.setNotification]); it stays when the job ends
 * (`JOB_END_NOTIFICATION_POLICY_DETACH`) until the final notification (same id) replaces it.
 * `onStopJob` cancels the transfer like a cancelled worker: the repository removes the half written
 * file.
 */
@RequiresApi(USER_INITIATED_JOBS_API)
abstract class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<Int, Job>()

    /** Runs the transfer; calls [finishJob] before it publishes the final notification. */
    protected abstract suspend fun runTransfer(params: JobParameters)

    /** The system stopped the job. [deliberate]: the user or the app did, so stay silent. */
    protected abstract fun stopped(params: JobParameters, deliberate: Boolean)

    protected fun attachNotification(
        params: JobParameters,
        notificationId: Int,
        notification: android.app.Notification,
    ) = setNotification(params, notificationId, notification, JOB_END_NOTIFICATION_POLICY_DETACH)

    protected fun finishJob(params: JobParameters) = jobFinished(params, false)

    override fun onStartJob(params: JobParameters): Boolean {
        running[params.jobId] = scope.launch {
            try {
                runTransfer(params)
            } finally {
                running.remove(params.jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val job = running.remove(params.jobId)
        job?.cancel()
        stopped(params, stopWasDeliberate(params.stopReason))
        // A new tap starts a new job; resuming a half written archive is not possible.
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

@RequiresApi(USER_INITIATED_JOBS_API)
@AndroidEntryPoint
class DataExportJobService : TransferJobService() {
    @Inject lateinit var transfer: DataExportTransfer
    @Inject lateinit var notifier: DataExportNotifier

    override suspend fun runTransfer(params: JobParameters) {
        val extras = params.extras
        val target = extras.getString(UserInitiatedJobs.EXTRA_URI)?.toUri()
        attachNotification(
            params,
            DataExportNotifier.NOTIFICATION_ID,
            notifier.progressNotification(0),
        )
        if (target == null) {
            finishJob(params)
            transfer.fail(ExportFailure.Failed)
            return
        }
        val request =
            DataExportRequest(
                target = target,
                fileName = extras.getString(UserInitiatedJobs.EXTRA_NAME).orEmpty(),
                format = ExportFormat.fromName(extras.getString(UserInitiatedJobs.EXTRA_FORMAT)),
                passphraseId = extras.getString(UserInitiatedJobs.EXTRA_PASSPHRASE_ID),
            )
        var estimate = 0L
        val result =
            transfer.run(request) { bytes ->
                attachNotification(
                    params,
                    DataExportNotifier.NOTIFICATION_ID,
                    notifier.progressNotification(bytes),
                )
                updateTransferredNetworkBytes(params, bytes, 0)
                if (bytes > estimate) {
                    estimate = grownEstimate(bytes, estimate)
                    updateEstimatedNetworkBytes(params, estimate, 0)
                }
            }
        finishJob(params)
        transfer.finish(request, result)
    }

    override fun stopped(params: JobParameters, deliberate: Boolean) {
        if (deliberate) transfer.cancelled() else transfer.fail(ExportFailure.Failed)
    }
}

@RequiresApi(USER_INITIATED_JOBS_API)
@AndroidEntryPoint
class DataRestoreJobService : TransferJobService() {
    @Inject lateinit var transfer: DataRestoreTransfer
    @Inject lateinit var notifier: DataRestoreNotifier

    override suspend fun runTransfer(params: JobParameters) {
        val extras = params.extras
        val source = extras.getString(UserInitiatedJobs.EXTRA_URI)?.toUri()
        val total = extras.getLong(UserInitiatedJobs.EXTRA_TOTAL, 0L)
        attachNotification(
            params,
            DataRestoreNotifier.NOTIFICATION_ID,
            notifier.progressNotification(uploadPercent(0, total)),
        )
        if (source == null) {
            finishJob(params)
            transfer.fail(RestoreFailure.Failed)
            return
        }
        val request =
            DataRestoreRequest(
                source,
                total,
                extras.getString(UserInitiatedJobs.EXTRA_PASSPHRASE_ID),
            )
        var estimate = 0L
        val result =
            transfer.run(request) { sent ->
                attachNotification(
                    params,
                    DataRestoreNotifier.NOTIFICATION_ID,
                    notifier.progressNotification(uploadPercent(sent, total)),
                )
                updateTransferredNetworkBytes(params, 0, sent)
                if (total <= 0 && sent > estimate) {
                    estimate = grownEstimate(sent, estimate)
                    updateEstimatedNetworkBytes(params, 0, estimate)
                }
            }
        finishJob(params)
        transfer.finish(request, result)
    }

    override fun stopped(params: JobParameters, deliberate: Boolean) {
        if (deliberate) transfer.cancelled() else transfer.fail(RestoreFailure.Failed)
    }
}
