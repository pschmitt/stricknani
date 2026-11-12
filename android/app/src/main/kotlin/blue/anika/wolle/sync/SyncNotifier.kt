package blue.anika.wolle.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.notify.NotificationImages
import blue.anika.wolle.notify.applyBrand
import blue.anika.wolle.notify.joinDetails
import blue.anika.wolle.data.impersonation.SessionMode
import blue.anika.wolle.ui.navigation.Route
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SNA-14: an optional local notification when a background sync finds changes. Scoped to that one
 * case only - notifying on an async backend job completing (e.g. link archiving) would need a
 * server-side way to detect that specific event, which doesn't exist yet.
 */
@Singleton
class SyncNotifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val images: NotificationImages,
) {

    /** Safe to call on every process start - `createNotificationChannel` is idempotent. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.sync_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = context.getString(R.string.sync_notification_channel_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * No-ops without a crash if the user hasn't granted `POST_NOTIFICATIONS` (Android 13+ requires
     * this at runtime; `MainActivity` requests it once, but the user can always deny it). Loads one
     * thumbnail through the app's image pipeline first; without one the notification is posted as
     * text only.
     */
    suspend fun notifySyncFoundChanges(summary: SyncSummary) {
        val headline = syncHeadline(summary) ?: return
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (
            !canPostAccountNotifications(
                Build.VERSION.SDK_INT,
                permissionGranted,
                notificationsEnabled,
            )
        ) {
            return
        }
        val items = summary.items()
        val image = images.load(items.firstOrNull { it.imageUrl != null }?.imageUrl)
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_stricknani)
                .setContentIntent(openIntent(headline, summary))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .applyBrand(context, context.getString(R.string.sync_notification_subtext))
        image?.let { builder.setLargeIcon(it.icon) }
        when (headline) {
            is SyncHeadline.Single -> {
                val text = context.getString(R.string.sync_notification_item_text)
                builder
                    .setContentTitle(
                        context.getString(R.string.sync_notification_item_title, headline.item.name)
                    )
                    .setContentText(text)
                image?.let {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(it.picture)
                            .bigLargeIcon(null as Bitmap?)
                            .setSummaryText(text)
                    )
                }
            }
            is SyncHeadline.Multiple -> {
                val names = items.map { it.name }
                builder
                    .setContentTitle(
                        context.getString(
                            R.string.sync_notification_multi_title,
                            countPhrase(headline.projects, headline.yarns),
                        )
                    )
                    .setContentText(names.joinToString(", "))
                val inbox = NotificationCompat.InboxStyle()
                names.take(MAX_INBOX_LINES).forEach(inbox::addLine)
                if (names.size > MAX_INBOX_LINES) {
                    inbox.addLine(
                        context.getString(
                            R.string.sync_notification_more,
                            names.size - MAX_INBOX_LINES,
                        )
                    )
                }
                inbox.setSummaryText(context.getString(R.string.sync_notification_item_text))
                builder.setStyle(inbox)
            }
            is SyncHeadline.Removed -> {
                builder
                    .setContentTitle(
                        context.getString(
                            R.string.sync_notification_removed_title,
                            countPhrase(headline.projects, headline.yarns),
                        )
                    )
                    .setContentText(context.getString(R.string.sync_notification_item_text))
            }
        }
        if (headline is SyncHeadline.Multiple && summary.removedTotal > 0) {
            builder.setSubText(
                joinDetails(
                    context.getString(R.string.sync_notification_subtext),
                    context.resources.getQuantityString(
                        R.plurals.sync_notification_removed_count,
                        summary.removedTotal,
                        summary.removedTotal,
                    ),
                )
            )
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission can be revoked between the check above and notify().
        }
    }

    /** "3 projects and 1 yarn", "2 yarns", "1 project". */
    private fun countPhrase(projects: Int, yarns: Int): String {
        val projectText =
            context.resources.getQuantityString(
                R.plurals.sync_notification_projects_count,
                projects,
                projects,
            )
        val yarnText =
            context.resources.getQuantityString(
                R.plurals.sync_notification_yarns_count,
                yarns,
                yarns,
            )
        return when {
            projects > 0 && yarns > 0 ->
                context.getString(R.string.sync_notification_join, projectText, yarnText)
            projects > 0 -> projectText
            else -> yarnText
        }
    }

    /**
     * Every notification opens something: the changed project or yarn when it is exactly one, else
     * the list that changed.
     */
    private fun openIntent(headline: SyncHeadline, summary: SyncSummary): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_SYNCED)
                .putExtra(EXTRA_SYNCED, summary.changes.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (headline is SyncHeadline.Single) {
            when (headline.kind) {
                SyncedKind.Project -> intent.putExtra(EXTRA_PROJECT_ID, headline.item.id)
                SyncedKind.Yarn -> intent.putExtra(EXTRA_YARN_ID, headline.item.id)
            }
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Opens the list named by [EXTRA_SYNCED] (a [SyncChanges] name). */
        const val ACTION_OPEN_SYNCED = "blue.anika.wolle.action.OPEN_SYNCED"
        const val EXTRA_SYNCED = "synced"

        /** With [ACTION_OPEN_SYNCED]: open this project (or [EXTRA_YARN_ID]'s yarn) instead. */
        const val EXTRA_PROJECT_ID = "synced_project_id"
        const val EXTRA_YARN_ID = "synced_yarn_id"
        private const val MAX_INBOX_LINES = 5
        private const val CHANNEL_ID = "sync_updates"
        private const val NOTIFICATION_ID = 1001
    }
}

/** What a background sync really changed in the cache. */
enum class SyncChanges {
    None,
    Projects,
    Yarns,
    ProjectsAndYarns;

    companion object {
        fun of(projects: Boolean, yarns: Boolean): SyncChanges =
            when {
                projects && yarns -> ProjectsAndYarns
                projects -> Projects
                yarns -> Yarns
                else -> None
            }
    }
}

enum class SyncedKind {
    Project,
    Yarn,
}

/** One project or yarn a sync changed, as the notification shows it. */
data class SyncedItem(
    val kind: SyncedKind,
    val id: Int,
    val name: String,
    /** Absolute URL of its preview image, if it has one. */
    val imageUrl: String? = null,
)

/** What a background sync changed: the new/modified items and how many were removed. */
data class SyncSummary(
    val projects: List<SyncedItem> = emptyList(),
    val yarns: List<SyncedItem> = emptyList(),
    val removedProjects: Int = 0,
    val removedYarns: Int = 0,
) {
    val removedTotal: Int
        get() = removedProjects + removedYarns

    val changes: SyncChanges
        get() =
            SyncChanges.of(
                projects = projects.isNotEmpty() || removedProjects > 0,
                yarns = yarns.isNotEmpty() || removedYarns > 0,
            )

    /** Projects first, then yarns. */
    fun items(): List<SyncedItem> = projects + yarns
}

/** What the notification of a [SyncSummary] says; chosen by [syncHeadline]. */
sealed interface SyncHeadline {
    /** Exactly one item changed and nothing was removed: titled with its name. */
    data class Single(val kind: SyncedKind, val item: SyncedItem) : SyncHeadline

    /** Several items changed: "3 projects and 1 yarn changed". */
    data class Multiple(val projects: Int, val yarns: Int) : SyncHeadline

    /** Rows only went away: "2 projects removed". */
    data class Removed(val projects: Int, val yarns: Int) : SyncHeadline
}

fun syncHeadline(summary: SyncSummary): SyncHeadline? {
    val items = summary.items()
    return when {
        items.size == 1 && summary.removedTotal == 0 ->
            SyncHeadline.Single(items.single().kind, items.single())
        items.isNotEmpty() -> SyncHeadline.Multiple(summary.projects.size, summary.yarns.size)
        summary.removedTotal > 0 ->
            SyncHeadline.Removed(summary.removedProjects, summary.removedYarns)
        else -> null
    }
}

/**
 * The screen a "synced from another device" notification opens: one project or yarn when its id is
 * given, else the list that changed.
 */
fun syncedRoute(changes: String?, projectId: Int? = null, yarnId: Int? = null): Route =
    when {
        projectId != null && projectId > 0 -> Route.ProjectDetail(projectId)
        yarnId != null && yarnId > 0 -> Route.YarnDetail(yarnId)
        else -> syncedListRoute(changes)
    }

private fun syncedListRoute(changes: String?): Route =
    when (changes) {
        SyncChanges.Projects.name -> Route.Projects
        SyncChanges.Yarns.name -> Route.Yarns
        else -> Route.Home
    }

internal fun canPostNotifications(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean = (sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted) && notificationsEnabled

/**
 * [canPostNotifications] for notifications about an account's own data (changes synced, timers,
 * imports). While an administrator acts as another account the phone must not notify about that
 * account's data: it is not the administrator's, and nothing may be registered for it.
 */
internal fun canPostAccountNotifications(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    impersonating: Boolean = SessionMode.impersonating,
): Boolean =
    !impersonating && canPostNotifications(sdkInt, permissionGranted, notificationsEnabled)
