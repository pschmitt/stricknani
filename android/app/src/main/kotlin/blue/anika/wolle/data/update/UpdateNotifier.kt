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
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.sync.canPostNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Notifications of the in-app update: progress, a needed confirmation, failures, "updated". */
@Singleton
class UpdateNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description =
                        context.getString(R.string.update_notification_channel_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** Shows the notification that matches [state], or removes it once there is nothing to say. */
    fun render(state: UpdateInstallState) {
        when (state) {
            is UpdateInstallState.Downloading ->
                notify(
                    builder(R.string.update_notification_downloading)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setProgress(
                            PROGRESS_MAX,
                            ((state.fraction ?: 0f) * PROGRESS_MAX).toInt(),
                            state.fraction == null,
                        )
                )
            UpdateInstallState.Verifying,
            UpdateInstallState.Installing ->
                notify(
                    builder(R.string.update_notification_installing)
                        .setOnlyAlertOnce(true)
                        .setProgress(0, 0, true)
                        // The app is killed by a successful install; do not leave this behind.
                        .setTimeoutAfter(INSTALL_TIMEOUT_MILLIS)
                )
            is UpdateInstallState.Failed -> showFailure(state)
            UpdateInstallState.AwaitingConfirmation,
            UpdateInstallState.Idle -> Unit
        }
    }

    fun showFailure(state: UpdateInstallState) {
        val failed = state as? UpdateInstallState.Failed ?: return
        notify(
            builder(R.string.update_notification_failed)
                .setContentText(context.getString(failed.failure.messageRes()))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(failed.failure.messageRes()))
                )
                .setAutoCancel(true)
        )
    }

    fun showConfirmation(confirm: Intent) {
        val pending =
            PendingIntent.getActivity(
                context,
                0,
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        notify(
            builder(R.string.update_notification_confirm)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        )
    }

    fun showInstalled() {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        notify(
            builder(R.string.update_notification_installed)
                .setContentIntent(open)
                .setAutoCancel(true)
        )
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun builder(@androidx.annotation.StringRes title: Int): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stricknani)
            .setContentTitle(context.getString(title))
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun notify(builder: NotificationCompat.Builder) {
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!canPostNotifications(Build.VERSION.SDK_INT, permissionGranted, enabled)) return
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and notify().
        }
    }

    private companion object {
        const val CHANNEL_ID = "app_update"
        const val NOTIFICATION_ID = 1002
        const val PROGRESS_MAX = 100
        const val INSTALL_TIMEOUT_MILLIS = 60_000L
    }
}
