package blue.anika.wolle.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.notify.applyBrand
import blue.anika.wolle.notify.displayVersion
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

    /** The version being installed, remembered for the notifications posted without one. */
    @Volatile private var version: String? = null

    /**
     * Shows the notification that matches [state], or removes it once there is nothing to say.
     * [updateVersion] is the version being installed; it is remembered for later calls.
     */
    fun render(state: UpdateInstallState, updateVersion: String? = null) {
        if (updateVersion != null) version = updateVersion
        val target = version?.let(::displayVersion)
        when (state) {
            is UpdateInstallState.Downloading ->
                notify(
                    builder(
                            titleOf(
                                R.string.update_notification_downloading_title,
                                R.string.update_notification_downloading,
                                target,
                            )
                        )
                        .setContentText(downloadText(state))
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
                    builder(
                            titleOf(
                                R.string.update_notification_installing_title,
                                R.string.update_notification_installing,
                                target,
                            )
                        )
                        .setContentText(
                            context.getString(R.string.update_notification_installing_text)
                        )
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

    /** "12.4 MB of 31.0 MB" while the size is known, else "12.4 MB downloaded". */
    private fun downloadText(state: UpdateInstallState.Downloading): String {
        val done = Formatter.formatShortFileSize(context, state.bytes)
        return if (state.total > 0) {
            context.getString(
                R.string.update_notification_downloading_progress,
                done,
                Formatter.formatShortFileSize(context, state.total),
            )
        } else {
            context.getString(R.string.update_notification_downloaded, done)
        }
    }

    private fun titleOf(@StringRes withVersion: Int, @StringRes without: Int, target: String?) =
        if (target != null) context.getString(withVersion, target) else context.getString(without)

    fun showFailure(state: UpdateInstallState) {
        val failed = state as? UpdateInstallState.Failed ?: return
        val message = context.getString(failed.failure.messageRes())
        val target = version?.let(::displayVersion)
        val title =
            if (target != null) {
                context.getString(R.string.update_notification_failed_version, target)
            } else {
                context.getString(R.string.update_notification_failed)
            }
        notify(
            builder(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
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
            builder(context.getString(R.string.update_notification_confirm))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        )
    }

    /** The app restarted on the new version: this is the version that now runs. */
    fun showInstalled() {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        notify(
            builder(
                    context.getString(
                        R.string.update_notification_installed_title,
                        displayVersion(BuildConfig.VERSION_NAME),
                    )
                )
                .setContentText(context.getString(R.string.update_notification_installed_text))
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
        )
        version = null
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun builder(title: String): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stricknani)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .applyBrand(context, context.getString(R.string.update_notification_channel_name))
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
