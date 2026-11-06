package blue.anika.wolle.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.ui.navigation.Route
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SNA-14: an optional local notification when a background sync finds changes. Scoped to that one
 * case only - notifying on an async backend job completing (e.g. link archiving) would need a
 * server-side way to detect that specific event, which doesn't exist yet.
 */
@Singleton
class SyncNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    /** Safe to call on every process start - `createNotificationChannel` is idempotent. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.sync_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = context.getString(R.string.sync_notification_channel_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * No-ops without a crash if the user hasn't granted `POST_NOTIFICATIONS` (Android 13+ requires
     * this at runtime; `MainActivity` requests it once, but the user can always deny it).
     */
    fun notifySyncFoundChanges(changes: SyncChanges) {
        if (changes == SyncChanges.None) return
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!canPostNotifications(Build.VERSION.SDK_INT, permissionGranted, notificationsEnabled)) {
            return
        }
        // Every notification opens something: here the list that changed.
        val open =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_OPEN_SYNCED)
                    .putExtra(EXTRA_SYNCED, changes.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text =
            context.getString(
                when (changes) {
                    SyncChanges.Projects -> R.string.sync_notification_projects
                    SyncChanges.Yarns -> R.string.sync_notification_yarns
                    else -> R.string.sync_notification_projects_and_yarns
                }
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_stricknani)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check above and notify().
        }
    }

    companion object {
        /** Opens the list named by [EXTRA_SYNCED] (a [SyncChanges] name). */
        const val ACTION_OPEN_SYNCED = "blue.anika.wolle.action.OPEN_SYNCED"
        const val EXTRA_SYNCED = "synced"
        private const val CHANNEL_ID = "sync_updates"
        private const val NOTIFICATION_ID = 1001
    }
}

/** What a background sync really changed in the cache. */
enum class SyncChanges {
    None,
    Projects,
    Yarns,
    ProjectsAndYarns;

    companion object {
        fun of(projects: Boolean, yarns: Boolean): SyncChanges =
            when {
                projects && yarns -> ProjectsAndYarns
                projects -> Projects
                yarns -> Yarns
                else -> None
            }
    }
}

/** The list a "synced from another device" notification opens. */
fun syncedRoute(changes: String?): Route =
    when (changes) {
        SyncChanges.Projects.name -> Route.Projects
        SyncChanges.Yarns.name -> Route.Yarns
        else -> Route.Home
    }

internal fun canPostNotifications(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean = (sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted) && notificationsEnabled
