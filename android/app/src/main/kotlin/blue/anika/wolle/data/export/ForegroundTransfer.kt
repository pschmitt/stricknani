package blue.anika.wolle.data.export

import android.app.Notification
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import blue.anika.wolle.data.export.DataExportNotifier.Companion.NOTIFICATION_ID
import kotlinx.coroutines.CancellationException

/**
 * The foreground service type of a user-started transfer worker at [sdkInt], or null when the
 * worker must not be a foreground service. API 34+ runs these transfers as user-initiated jobs (no
 * time limit, no foreground service), and a worker that still runs there because the system refused
 * the job stays a plain worker and accepts WorkManager's ten minute limit. API 29-33 use
 * `dataSync`; API 26-28 have no service types, so the type is 0.
 */
fun foregroundTypeFor(sdkInt: Int): Int? =
    when {
        sdkInt >= USER_INITIATED_JOBS_API -> null
        sdkInt >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        sdkInt >= 26 -> 0
        else -> null
    }

/** The foreground notification ids; distinct from the result ids, see [promoteToForeground]. */
const val EXPORT_FOREGROUND_ID = NOTIFICATION_ID + 10
const val RESTORE_FOREGROUND_ID = DataRestoreNotifier.NOTIFICATION_ID + 10

/** The [ForegroundInfo] for [sdkInt], or null when no foreground service applies. */
fun foregroundInfoFor(sdkInt: Int, id: Int, notification: Notification): ForegroundInfo? =
    foregroundTypeFor(sdkInt)?.let { ForegroundInfo(id, notification, it) }

/**
 * Makes this user-started worker a `dataSync` foreground service so the system does not stop it
 * after about ten minutes (API 26-33). Returns whether it is one now. Never fails the transfer: if
 * the system refuses (for example `ForegroundServiceStartNotAllowedException` on API 31-33 when
 * started from the background) the worker simply runs as a normal worker.
 *
 * The foreground notification has its own id ([EXPORT_FOREGROUND_ID], [RESTORE_FOREGROUND_ID]), not
 * the id of the result notification: WorkManager removes the foreground notification when the
 * worker ends, which would otherwise take the finished/failed notification with it.
 */
suspend fun CoroutineWorker.promoteToForeground(
    sdkInt: Int,
    id: Int,
    notification: Notification,
): Boolean {
    val info = foregroundInfoFor(sdkInt, id, notification) ?: return false
    return try {
        setForeground(info)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
