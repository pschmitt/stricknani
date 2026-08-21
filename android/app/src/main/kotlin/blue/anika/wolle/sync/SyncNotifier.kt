package blue.anika.wolle.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import blue.anika.wolle.R
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
    fun notifySyncFoundChanges() {
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
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.sync_notification_content))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check above and notify().
        }
    }

    private companion object {
        const val CHANNEL_ID = "sync_updates"
        const val NOTIFICATION_ID = 1001
    }
}

internal fun canPostNotifications(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean = (sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted) && notificationsEnabled
