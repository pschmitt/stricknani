package blue.anika.wolle.data.export

import android.Manifest
import android.app.Notification
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

    /**
     * Two channels: progress is silent (low importance), results are normal so a finished or failed
     * download or restore is shown, not only an icon (bedtime and quiet modes collapse low ones).
     */
    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val progress =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.data_export_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description =
                        context.getString(R.string.data_export_notification_channel_description)
                }
        val results =
            NotificationChannel(
                    RESULTS_CHANNEL_ID,
                    context.getString(R.string.data_transfer_results_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description =
                        context.getString(R.string.data_transfer_results_channel_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannels(listOf(progress, results))
    }

    /** [bytes] so far; the total is unknown (the server streams without a length). */
    fun progress(bytes: Long) = notify(progressBuilder(bytes))

    /**
     * The progress notification as a [Notification], for a user-initiated job: the system posts it
     * itself (`JobService.setNotification`). Clickable like [progress]: it opens Backups.
     */
    fun progressNotification(bytes: Long): Notification =
        progressBuilder(bytes).setGroup(groupFor(NOTIFICATION_ID)).build()

    private fun progressBuilder(bytes: Long): NotificationCompat.Builder =
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

    /**
     * A scheduled backup could not write its data download into the folder. Opens Backups, where
     * the schedule is set up.
     */
    fun scheduledDataFailed() {
        val text = context.getString(R.string.scheduled_backup_data_failed_text)
        post(
            SCHEDULED_NOTIFICATION_ID,
            builder(R.string.scheduled_backup_data_failed_title, openBackups(), results = true)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true),
        )
    }

    fun succeeded(
        target: Uri,
        fileName: String,
        format: ExportFormat,
        encrypted: Boolean = false,
    ) {
        notify(
            builder(
                    R.string.data_export_notification_success_title,
                    openFile(target, format, encrypted),
                    results = true,
                )
                .setContentText(fileName)
                .setAutoCancel(true)
        )
    }

    fun failed(reason: ExportFailure) {
        val text = context.getString(failureMessageRes(reason))
        notify(
            builder(R.string.data_export_notification_failed_title, openBackups(), results = true)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
        )
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /** [results]: a finished or failed transfer (normal channel); else progress (silent). */
    internal fun builder(
        @androidx.annotation.StringRes title: Int,
        contentIntent: PendingIntent,
        results: Boolean = false,
    ): NotificationCompat.Builder {
        ensureChannels()
        return NotificationCompat.Builder(context, channelFor(results))
            .setSmallIcon(R.drawable.ic_stat_stricknani)
            .setContentTitle(context.getString(title))
            .setContentIntent(contentIntent)
            .setPriority(
                if (results) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_LOW
            )
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

    private fun openFile(target: Uri, format: ExportFormat, encrypted: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_FILE,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_EXPORT)
                .putExtra(EXTRA_EXPORT_URI, target.toString())
                .putExtra(EXTRA_EXPORT_MIME, format.mimeTypeFor(encrypted))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notify(builder: NotificationCompat.Builder) = post(NOTIFICATION_ID, builder)

    /** Posts [builder] under [id] if notifications are allowed; the restore shares this channel. */
    internal fun post(id: Int, builder: NotificationCompat.Builder) {
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!canPostNotifications(Build.VERSION.SDK_INT, permissionGranted, enabled)) return
        try {
            NotificationManagerCompat.from(context)
                .notify(id, builder.setGroup(groupFor(id)).build())
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
        private const val RESULTS_CHANNEL_ID = "data_transfer_results"

        /**
         * Each notification id is its own group. Without one, Android bundles several of the app's
         * notifications into an automatic group that takes the silent channel of an older one,
         * which hides a finished download or restore in the collapsed row.
         */
        internal fun groupFor(id: Int): String = "blue.anika.wolle.notification.$id"

        /** The channel of a progress ([results] false) or result notification. */
        internal fun channelFor(results: Boolean): String =
            if (results) RESULTS_CHANNEL_ID else CHANNEL_ID

        /** The id of the download's notification, also the one a job hands to the system. */
        const val NOTIFICATION_ID = 1003
        private const val SCHEDULED_NOTIFICATION_ID = 1007
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
