package blue.anika.wolle.data.export

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.sync.SyncChanges
import blue.anika.wolle.sync.SyncNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications of a restore, on the data download channel. Progress and failure open Settings ->
 * Backups, success opens the projects list (through the same action "synced from another device"
 * uses).
 */
@Singleton
class DataRestoreNotifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val channel: DataExportNotifier,
) {

    /** Percent while uploading when the size is known, else "Restoring…" without a number. */
    fun progress(percent: Int?) = channel.post(NOTIFICATION_ID, progressBuilder(percent))

    /** The progress notification for a user-initiated job (`JobService.setNotification`). */
    fun progressNotification(percent: Int?): Notification =
        progressBuilder(percent).setGroup(DataExportNotifier.groupFor(NOTIFICATION_ID)).build()

    private fun progressBuilder(percent: Int?): NotificationCompat.Builder {
        val builder =
            builder(R.string.data_restore_notification_progress_title, openBackups())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        if (percent != null) {
            builder.setContentText(context.getString(R.string.data_restore_uploading, percent))
            builder.setProgress(100, percent, false)
        } else {
            builder.setContentText(context.getString(R.string.data_restore_processing))
            builder.setProgress(0, 0, true)
        }
        return builder
    }

    fun succeeded(summary: RestoreSummary) {
        val text = restoreSummaryText(context, summary)
        channel.post(
            NOTIFICATION_ID,
            builder(
                    R.string.data_restore_notification_success_title,
                    openProjects(),
                    results = true,
                )
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true),
        )
    }

    fun failed(reason: RestoreFailure) {
        val text = context.getString(restoreFailureMessageRes(reason))
        channel.post(
            NOTIFICATION_ID,
            builder(R.string.data_restore_notification_failed_title, openBackups(), results = true)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true),
        )
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun builder(
        @StringRes title: Int,
        contentIntent: PendingIntent,
        results: Boolean = false,
    ) = channel.builder(title, contentIntent, results)

    private fun openBackups(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_BACKUPS,
            Intent(context, MainActivity::class.java)
                .setAction(DataExportNotifier.ACTION_OPEN_BACKUPS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openProjects(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_PROJECTS,
            Intent(context, MainActivity::class.java)
                .setAction(SyncNotifier.ACTION_OPEN_SYNCED)
                .putExtra(SyncNotifier.EXTRA_SYNCED, SyncChanges.Projects.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** The id of the restore's notification, also the one a job hands to the system. */
        const val NOTIFICATION_ID = 1005
        private const val REQUEST_BACKUPS = 1005
        private const val REQUEST_PROJECTS = 1006
    }
}

/** The user-facing text of a failed restore. */
fun restoreFailureMessageRes(reason: RestoreFailure): Int =
    when (reason) {
        RestoreFailure.InvalidArchive -> R.string.data_restore_error_invalid_archive
        RestoreFailure.UnsupportedVersion -> R.string.data_restore_error_unsupported_version
        RestoreFailure.TooLarge -> R.string.data_restore_error_too_large
        RestoreFailure.AlreadyRunning -> R.string.data_restore_error_already_running
        RestoreFailure.PassphraseRequired -> R.string.data_restore_error_passphrase_required
        RestoreFailure.WrongPassphrase -> R.string.data_restore_error_wrong_passphrase
        RestoreFailure.Unauthorized,
        RestoreFailure.Failed -> R.string.data_restore_error_failed
    }

/** `Projects: N · Yarns: N · Photos and files: N`, shared by the notification and the screen. */
fun restoreSummaryText(context: Context, summary: RestoreSummary): String =
    joinSummary(
        listOf(
            countLine(context.getString(R.string.data_restore_projects), summary.projects),
            countLine(context.getString(R.string.data_restore_yarns), summary.yarns),
            countLine(context.getString(R.string.data_restore_media), summary.mediaFiles),
        )
    )
