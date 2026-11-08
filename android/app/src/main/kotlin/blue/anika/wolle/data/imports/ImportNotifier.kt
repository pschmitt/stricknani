package blue.anika.wolle.data.imports

import android.Manifest
import android.app.Notification
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
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.notify.NotificationImages
import blue.anika.wolle.notify.applyBrand
import blue.anika.wolle.sync.canPostNotifications
import blue.anika.wolle.ui.common.importImagePath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications of a background link import ([ImportWorker]): the progress of the short foreground
 * service on Android 8 to 11, and the result when nobody is looking at the import dialog. Tapping
 * the result opens the import dialog on it.
 */
@Singleton
class ImportNotifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val images: NotificationImages,
    private val mediaUrlResolver: MediaUrlResolver,
) {

    fun progressNotification(kind: ImportKind): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stricknani)
            .setContentTitle(
                context.getString(
                    when (kind) {
                        ImportKind.Project -> R.string.import_notification_progress_project
                        ImportKind.Yarn -> R.string.import_notification_progress_yarn
                    }
                )
            )
            .setContentText(context.getString(R.string.import_notification_progress_text))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .applyBrand(context, context.getString(R.string.import_notification_subtext))
            .build()
    }

    /** "Pattern ready to review" with the name and the first picture found, or the failure. */
    suspend fun finished(kind: ImportKind, result: PendingImport) {
        ensureChannels()
        val builder =
            NotificationCompat.Builder(context, RESULTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_stricknani)
                .setContentIntent(openImport(kind, result.url))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .applyBrand(context, context.getString(R.string.import_notification_subtext))
        when (result) {
            is PendingImport.ProjectReady ->
                ready(
                    builder,
                    R.string.import_notification_ready_project,
                    result.preview.request.name,
                    result.preview.imageUrls.firstOrNull(),
                )
            is PendingImport.YarnReady ->
                ready(
                    builder,
                    R.string.import_notification_ready_yarn,
                    result.preview.request.name,
                    result.preview.imageUrls.firstOrNull(),
                )
            is PendingImport.Failed -> {
                val text = context.getString(R.string.import_notification_failed_text)
                builder
                    .setContentTitle(context.getString(R.string.import_notification_failed))
                    .setContentText(hostOf(result.url) ?: text)
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(listOfNotNull(hostOf(result.url), text).joinToString("\n"))
                    )
            }
            is PendingImport.Running -> return
        }
        post(notificationId(kind), builder)
    }

    private suspend fun ready(
        builder: NotificationCompat.Builder,
        title: Int,
        name: String,
        imageUrl: String?,
    ) {
        builder.setContentTitle(context.getString(title)).setContentText(name)
        // Through the server's image proxy: the app never contacts the pattern site itself.
        val image = images.load(imageUrl?.let { mediaUrlResolver.resolve(importImagePath(it)) })
        if (image != null) {
            builder
                .setLargeIcon(image.icon)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(image.picture)
                        .bigLargeIcon(null as android.graphics.Bitmap?)
                        .setSummaryText(name)
                )
        }
    }

    private fun openImport(kind: ImportKind, url: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(kind),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_IMPORT)
                .putExtra(EXTRA_IMPORT_KIND, kind.name)
                .putExtra(EXTRA_IMPORT_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The result is shown in the dialog now: remove its notification. */
    fun cancel(kind: ImportKind) =
        NotificationManagerCompat.from(context).cancel(notificationId(kind))

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!canPostNotifications(Build.VERSION.SDK_INT, permissionGranted, enabled)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and notify().
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val progress =
            NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    context.getString(R.string.import_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description =
                        context.getString(R.string.import_notification_channel_description)
                }
        val results =
            NotificationChannel(
                    RESULTS_CHANNEL_ID,
                    context.getString(R.string.import_notification_results_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description =
                        context.getString(R.string.import_notification_results_channel_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannels(listOf(progress, results))
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

    companion object {
        /** Opens the import dialog of [EXTRA_IMPORT_KIND] on the result for [EXTRA_IMPORT_URL]. */
        const val ACTION_OPEN_IMPORT = "blue.anika.wolle.action.OPEN_IMPORT"
        const val EXTRA_IMPORT_KIND = "blue.anika.wolle.extra.IMPORT_KIND"
        const val EXTRA_IMPORT_URL = "blue.anika.wolle.extra.IMPORT_URL"

        private const val PROGRESS_CHANNEL_ID = "link_imports"
        private const val RESULTS_CHANNEL_ID = "link_import_results"

        fun notificationId(kind: ImportKind): Int =
            when (kind) {
                ImportKind.Project -> 1030
                ImportKind.Yarn -> 1031
            }
    }
}
