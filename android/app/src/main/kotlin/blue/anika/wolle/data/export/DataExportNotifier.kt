package blue.anika.wolle.data.export

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.sync.canPostNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications of the data download. Every one opens something: progress and failure open Settings
 * -> Backups, success opens the saved file (see `MainActivity`, which falls back to Backups when no
 * app can open the archive).
 */
@Singleton
class DataExportNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.data_export_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description =
                        context.getString(R.string.data_export_notification_channel_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** [bytes] so far; the total is unknown (the server streams without a length). */
    fun progress(bytes: Long) {
        notify(
            builder(R.string.data_export_notification_progress_title, openBackups())
                .setContentText(
                    context.getString(
                        R.string.data_export_notification_progress_text,
                        Formatter.formatShortFileSize(context, bytes),
                    )
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
        )
    }

    fun succeeded(target: Uri, fileName: String, format: ExportFormat) {
        notify(
            builder(R.string.data_export_notification_success_title, openFile(target, format))
                .setContentText(fileName)
                .setAutoCancel(true)
        )
    }

    fun failed(reason: ExportFailure) {
        val text = context.getString(failureMessageRes(reason))
        notify(
            builder(R.string.data_export_notification_failed_title, openBackups())
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
        )
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun builder(
        @androidx.annotation.StringRes title: Int,
        contentIntent: PendingIntent,
    ): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stricknani)
            .setContentTitle(context.getString(title))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun openBackups(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_BACKUPS,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BACKUPS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openFile(target: Uri, format: ExportFormat): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_FILE,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_EXPORT)
                .putExtra(EXTRA_EXPORT_URI, target.toString())
                .putExtra(EXTRA_EXPORT_MIME, format.mimeType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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

    companion object {
        /** Opens Settings -> Backups. */
        const val ACTION_OPEN_BACKUPS = "blue.anika.wolle.action.OPEN_BACKUPS"

        /**
         * Opens the saved archive named by [EXTRA_EXPORT_URI], or Backups when nothing can open it.
         */
        const val ACTION_OPEN_EXPORT = "blue.anika.wolle.action.OPEN_EXPORT"
        const val EXTRA_EXPORT_URI = "export_uri"
        const val EXTRA_EXPORT_MIME = "export_mime"
        private const val CHANNEL_ID = "data_export"
        private const val NOTIFICATION_ID = 1003
        private const val REQUEST_BACKUPS = 1003
        private const val REQUEST_FILE = 1004
    }
}

/** The user-facing text of a failed download. */
fun failureMessageRes(reason: ExportFailure): Int =
    when (reason) {
        ExportFailure.AlreadyRunning -> R.string.data_export_error_already_running
        ExportFailure.Unauthorized,
        ExportFailure.Failed -> R.string.data_export_error_failed
    }
