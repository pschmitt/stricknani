package blue.anika.wolle.data.update

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
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.sync.canPostNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "new version available" notification (GitHub APK builds only). It has its own channel, so the
 * user can switch it off in Android's settings, and one fixed id, so a newer announcement replaces
 * an older one. Tapping it opens Settings -> About, where the Update button is.
 */
@Singleton
class UpdateAvailableNotifier
@Inject
constructor(@ApplicationContext private val context: Context) {

    /** Posts the notification; false when it could not be shown (no permission, switched off). */
    fun show(version: String): Boolean {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return false
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!canPostNotifications(Build.VERSION.SDK_INT, permissionGranted, enabled)) return false
        ensureChannel()
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_OPEN_UPDATE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text = context.getString(R.string.update_available_notification_text, version)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_stricknani)
                .setContentTitle(context.getString(R.string.update_available_notification_title))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_available_channel_name),
                    // Silent: one calm line a day, not worth a sound.
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = context.getString(R.string.update_available_channel_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    companion object {
        /** Set on the intent of the notification; [MainActivity] turns it into "open About". */
        const val ACTION_OPEN_UPDATE = "blue.anika.wolle.action.OPEN_UPDATE"
        private const val CHANNEL_ID = "app_update_available"
        private const val NOTIFICATION_ID = 1003
    }
}
