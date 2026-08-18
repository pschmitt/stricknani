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
            NotificationChannel(CHANNEL_ID, "Sync updates", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "New changes found during a background sync" }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * No-ops without a crash if the user hasn't granted `POST_NOTIFICATIONS` (Android 13+ requires
     * this at runtime; `MainActivity` requests it once, but the user can always deny it).
     */
    fun notifySyncFoundChanges() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("New changes synced from your server")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "sync_updates"
        const val NOTIFICATION_ID = 1001
    }
}
