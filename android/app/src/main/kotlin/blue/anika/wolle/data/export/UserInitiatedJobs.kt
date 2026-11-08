package blue.anika.wolle.data.export

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Schedules the download and the restore as user-initiated data transfer jobs (API 34+): no ten
 * minute limit, listed in the system's Task Manager, and the user can stop them there. They need
 * `RUN_USER_INITIATED_JOBS` (a normal permission, no Play declaration) and must be scheduled while
 * the app is visible, which a button tap satisfies. The app still has no foreground service.
 */
@Singleton
class UserInitiatedJobs @Inject constructor(@ApplicationContext private val context: Context) {

    /** Whether the system accepted the job; false means the caller falls back to a worker. */
    fun scheduleExport(request: DataExportRequest): Boolean =
        schedule(
            JOB_ID_EXPORT,
            DataExportJobService::class.java,
            // The server streams without a length: unknown download size, nothing uploaded.
            download = JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
            upload = 0L,
            extras =
                PersistableBundle().apply {
                    putString(EXTRA_URI, request.target.toString())
                    putString(EXTRA_NAME, request.fileName)
                    putString(EXTRA_FORMAT, request.format.name)
                    // The extras are persisted: only the id of the in-memory passphrase goes in.
                    request.passphraseId?.let { putString(EXTRA_PASSPHRASE_ID, it) }
                },
        )

    fun scheduleRestore(request: DataRestoreRequest): Boolean =
        schedule(
            JOB_ID_RESTORE,
            DataRestoreJobService::class.java,
            download = 0L,
            upload =
                if (request.totalBytes > 0) request.totalBytes
                else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
            extras =
                PersistableBundle().apply {
                    putString(EXTRA_URI, request.source.toString())
                    putLong(EXTRA_TOTAL, request.totalBytes)
                    request.passphraseId?.let { putString(EXTRA_PASSPHRASE_ID, it) }
                },
        )

    private fun schedule(
        jobId: Int,
        service: Class<*>,
        download: Long,
        upload: Long,
        extras: PersistableBundle,
    ): Boolean {
        if (Build.VERSION.SDK_INT < USER_INITIATED_JOBS_API) return false
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val info =
            JobInfo.Builder(jobId, ComponentName(context, service))
                .setUserInitiated(true)
                .setRequiredNetwork(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                )
                .setEstimatedNetworkBytes(download, upload)
                .setExtras(extras)
                .build()
        return try {
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (e: SecurityException) {
            Timber.w(e, "User-initiated job refused, falling back to a worker")
            false
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "User-initiated job invalid, falling back to a worker")
            false
        }
    }

    companion object {
        const val JOB_ID_EXPORT = 7101
        const val JOB_ID_RESTORE = 7102
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PASSPHRASE_ID = "passphrase_id"
    }
}
