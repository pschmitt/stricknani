package blue.anika.wolle.data.imports

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
import blue.anika.wolle.data.api.v2.ImportJobDto
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
 * "Pattern ready to review" (or "Yarn ...", or "The import did not work") for an import draft that
 * finished while nobody was looking. Tapping it opens the editor on the draft.
 */
@Singleton
class ImportNotifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val images: NotificationImages,
    private val mediaUrlResolver: MediaUrlResolver,
) {

    /** Announces a finished [draft] with its name and first picture, or the failure. */
    suspend fun finished(draft: ImportJobDto) {
        ensureChannel()
        val builder =
            NotificationCompat.Builder(context, RESULTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_stricknani)
                .setContentIntent(openDraft(draft))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .applyBrand(context, context.getString(R.string.import_notification_subtext))
        if (draft.isReady) ready(builder, draft) else failed(builder, draft)
        post(notificationId(draft), builder)
    }

    private suspend fun ready(builder: NotificationCompat.Builder, draft: ImportJobDto) {
        val name = draft.title?.takeIf { it.isNotBlank() } ?: hostOf(draft.sourceUrl).orEmpty()
        builder
            .setContentTitle(
                context.getString(
                    if (draft.isYarn) R.string.import_notification_ready_yarn
                    else R.string.import_notification_ready_project
                )
            )
            .setContentText(name)
        // Through the server's image proxy: the app never contacts the pattern site itself.
        val image =
            images.load(draft.imageUrl?.let { mediaUrlResolver.resolve(importImagePath(it)) })
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

    private fun failed(builder: NotificationCompat.Builder, draft: ImportJobDto) {
        val host = hostOf(draft.sourceUrl)
        val text = context.getString(R.string.import_notification_failed_text)
        builder
            .setContentTitle(context.getString(R.string.import_notification_failed))
            .setContentText(host ?: text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(listOfNotNull(host, text).joinToString("\n"))
            )
    }

    private fun openDraft(draft: ImportJobDto): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(draft),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_DRAFT)
                .putExtra(EXTRA_DRAFT_ID, draft.id)
                .putExtra(EXTRA_DRAFT_YARN, draft.isYarn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
            ?.createNotificationChannel(results)
    }

    private fun hostOf(url: String?): String? = url?.let {
        runCatching { URI(it).host?.removePrefix("www.") }.getOrNull()
    }

    companion object {
        /** Opens the editor of [EXTRA_DRAFT_YARN] on the draft [EXTRA_DRAFT_ID]. */
        const val ACTION_OPEN_DRAFT = "blue.anika.wolle.action.OPEN_IMPORT_DRAFT"
        const val EXTRA_DRAFT_ID = "blue.anika.wolle.extra.IMPORT_DRAFT_ID"
        const val EXTRA_DRAFT_YARN = "blue.anika.wolle.extra.IMPORT_DRAFT_YARN"

        private const val RESULTS_CHANNEL_ID = "link_import_results"
        private const val ID_BASE = 1100

        /** One notification per draft, so two finished imports do not replace each other. */
        fun notificationId(draft: ImportJobDto): Int = ID_BASE + (draft.id.hashCode() and 0xfff)
    }
}
