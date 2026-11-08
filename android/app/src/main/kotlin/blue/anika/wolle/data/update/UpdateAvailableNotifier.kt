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
import blue.anika.wolle.notify.VersionKind
import blue.anika.wolle.notify.applyBrand
import blue.anika.wolle.notify.displayVersion
import blue.anika.wolle.notify.versionKind
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

    /**
     * Posts the notification; false when it could not be shown (no permission, switched off).
     * [releaseNotes] is the plain-text summary of the release, shown when the notification is
     * expanded.
     */
    fun show(version: String, releaseNotes: String? = null): Boolean {
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
        val open = openUpdateScreen(REQUEST_OPEN)
        val offered = displayVersion(version)
        val title =
            when (versionKind(version)) {
                VersionKind.Release ->
                    context.getString(R.string.update_available_notification_title, offered)
                VersionKind.Build ->
                    context.getString(R.string.update_available_notification_title_build, offered)
            }
        val text =
            context.getString(R.string.update_available_notification_text, currentVersionText())
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_stricknani)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                // The button opens the same screen as a tap: Settings -> About has the Update
                // button, which handles the install permission and confirmation.
                .addAction(
                    0,
                    context.getString(R.string.update_available_download),
                    openUpdateScreen(REQUEST_UPDATE),
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .applyBrand(context, context.getString(R.string.update_available_channel_name))
        if (!releaseNotes.isNullOrBlank()) {
            val heading = context.getString(R.string.update_available_notification_notes)
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText("$text\n\n$heading\n$releaseNotes")
                    .setSummaryText(context.getString(R.string.update_available_channel_name))
            )
        }
        val notification = builder.build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun openUpdateScreen(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The running version: `1.5.1`, or `build abc1234` for a rolling build. */
    private fun currentVersionText(): String =
        when (val installed = InstalledBuild.parse(BuildConfig.GIT_REVISION)) {
            is InstalledBuild.Rolling ->
                context.getString(
                    R.string.update_version_build,
                    installed.commit.take(SHORT_COMMIT_LENGTH),
                )
            else -> displayVersion(BuildConfig.VERSION_NAME)
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
        // Its own id: 1003 is the data download's (DataExportNotifier), which replaced this one.
        private const val NOTIFICATION_ID = 1008
        private const val REQUEST_OPEN = 0
        private const val REQUEST_UPDATE = 1
        private const val SHORT_COMMIT_LENGTH = 7
    }
}
