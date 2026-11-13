package blue.anika.wolle.ui.home

import blue.anika.wolle.data.api.dto.ActivityItemDto
import blue.anika.wolle.data.api.dto.AuditSubjectDto
import blue.anika.wolle.data.api.dto.RESOURCE_STATE_IN_TRASH
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.ui.admin.DayLabel
import blue.anika.wolle.ui.admin.dayLabelFor
import blue.anika.wolle.ui.common.UserRef
import blue.anika.wolle.ui.common.activityActorRef
import blue.anika.wolle.ui.history.fieldNounRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Pure rules behind Home's "Recent activity": who did it, how many changed fields are named and what
 * the row does when tapped. The action is worded like the project history ([auditActionPresentation]
 * and the sharing sentences), so both views agree. The Compose layer only turns this into text.
 */

/** How many rows Home shows (its first page; "See all" opens the full page). */
const val ACTIVITY_PAGE_SIZE = 6

/** The full activity page loads more rows at a time. */
const val ACTIVITY_FULL_PAGE_SIZE = 20

/** At most this many field labels are named on a row; the rest become "N more". */
const val ACTIVITY_MAX_FIELDS = 3

/** What a timeline node draws: the kind of change, not the thing that changed. */
enum class ActivityIconKind {
    /** created */
    Sparkle,
    /** updated */
    Pencil,
    /** a photo was added */
    Camera,
    /** deleted */
    Trash,
    /** something was removed (destructive, red like a deletion) */
    Minus,
    /** a share stopped or was left (neutral: not a deletion) */
    Unshare,
    /** restored from the trash */
    Restore,
    /** the project timer */
    Timer,
    /** sharing and ownership */
    Share,
    /** a file other than a photo was added */
    Attachment,
    /** anything the app does not know by name */
    Other,
}

private val exactIcons =
    mapOf(
        "created" to ActivityIconKind.Sparkle,
        "step_created" to ActivityIconKind.Sparkle,
        "updated" to ActivityIconKind.Pencil,
        "step_updated" to ActivityIconKind.Pencil,
        "photo_promoted" to ActivityIconKind.Pencil,
        "title_image_promoted" to ActivityIconKind.Pencil,
        "uploaded" to ActivityIconKind.Camera,
        "photo_uploaded" to ActivityIconKind.Camera,
        "title_image_uploaded" to ActivityIconKind.Camera,
        "stitch_sample_image_uploaded" to ActivityIconKind.Camera,
        "step_image_uploaded" to ActivityIconKind.Camera,
        "attachment_uploaded" to ActivityIconKind.Attachment,
        "deleted" to ActivityIconKind.Trash,
        "trashed" to ActivityIconKind.Trash,
        "purged" to ActivityIconKind.Trash,
        "restored" to ActivityIconKind.Restore,
        "step_deleted" to ActivityIconKind.Trash,
        "attachment_deleted" to ActivityIconKind.Trash,
        "image_deleted" to ActivityIconKind.Trash,
        "photo_deleted" to ActivityIconKind.Trash,
        "removed" to ActivityIconKind.Minus,
        "timer_started" to ActivityIconKind.Timer,
        "timer_stopped" to ActivityIconKind.Timer,
        "timer_total_changed" to ActivityIconKind.Timer,
        "shared" to ActivityIconKind.Share,
        "share_role_changed" to ActivityIconKind.Share,
        "share_revoked" to ActivityIconKind.Unshare,
        "share_left" to ActivityIconKind.Unshare,
        "ownership_transferred" to ActivityIconKind.Share,
        "step_trashed" to ActivityIconKind.Trash,
        "step_restored" to ActivityIconKind.Restore,
        "step_purged" to ActivityIconKind.Trash,
    )

/** The node icon of an audit action; unknown actions are read by their suffix. */
fun activityIconKind(action: String): ActivityIconKind {
    val key = action.lowercase()
    exactIcons[key]?.let {
        return it
    }
    return when {
        key.endsWith("_created") -> ActivityIconKind.Sparkle
        key.endsWith("_updated") || key.endsWith("_changed") -> ActivityIconKind.Pencil
        key.endsWith("_uploaded") -> ActivityIconKind.Camera
        key.endsWith("_deleted") -> ActivityIconKind.Trash
        key.endsWith("_removed") -> ActivityIconKind.Minus
        key.endsWith("_trashed") || key.endsWith("_purged") -> ActivityIconKind.Trash
        key.endsWith("_restored") -> ActivityIconKind.Restore
        else -> ActivityIconKind.Other
    }
}

/** Where a tap on a row leads. */
sealed interface ActivityTarget {
    data class Project(val id: Int) : ActivityTarget

    data class Yarn(val id: Int) : ActivityTarget

    /**
     * The item is in the trash: its page would answer 404 and there is no read-only item view, so
     * the row leads to the Trash screen.
     */
    data object Trash : ActivityTarget
}

/** One row, ready to draw. */
data class ActivityRowModel(
    val key: Long,
    /** The audit action; worded by the project history's labels. */
    val action: String,
    /** No account caused it: reads "Purged automatically after 90 days". */
    val system: Boolean,
    val icon: ActivityIconKind,
    /** Who did it: You, a display name, an administrator, System or Unknown. */
    val actor: UserRef,
    /** The other account of a sharing event ("Shared with Anna"). */
    val subject: AuditSubjectDto?,
    val isYarn: Boolean,
    val entityName: String?,
    /** More than one edit folded into this row (an autosave burst); 1 otherwise. */
    val count: Int,
    /**
     * String resources of the changed-field nouns, without repeats, at most [ACTIVITY_MAX_FIELDS].
     */
    val fieldNounRes: List<Int>,
    /** Changed fields beyond [fieldNounRes] that are not shown. */
    val moreFields: Int,
    val thumbnailUrl: String?,
    val target: ActivityTarget?,
    val createdAt: String,
) {
    /** A deleted resource: muted and not tappable. An item in the trash leads to the trash. */
    val deleted: Boolean
        get() = target == null
}

/** The row for a feed item. */
fun activityRow(item: ActivityItemDto): ActivityRowModel {
    val isYarn = item.entityType == "yarn"
    val nouns = item.changedFields.mapNotNull(::fieldNounRes).distinct()
    val inTrash = item.state == RESOURCE_STATE_IN_TRASH
    val target =
        if (inTrash) {
            ActivityTarget.Trash
        } else if (!item.exists) {
            null
        } else if (isYarn) {
            ActivityTarget.Yarn(item.entityId)
        } else if (item.entityType == "project") {
            ActivityTarget.Project(item.entityId)
        } else {
            null
        }
    return ActivityRowModel(
        key = item.id,
        action = item.action,
        system = item.system,
        icon = activityIconKind(item.action),
        actor = activityActorRef(item),
        subject = item.subject,
        isYarn = isYarn,
        entityName = item.entityName?.takeIf { it.isNotBlank() },
        count = item.count.coerceAtLeast(1),
        fieldNounRes = nouns.take(ACTIVITY_MAX_FIELDS),
        moreFields = (nouns.size - ACTIVITY_MAX_FIELDS).coerceAtLeast(0),
        // The owner and administrators still see a trashed item's picture; the server sends none
        // to anyone else.
        thumbnailUrl = item.thumbnailUrl.takeIf { item.exists || inTrash },
        target = target,
        createdAt = item.createdAt,
    )
}

/** Appends a page, dropping items already present (a new edit can shift the page boundary). */
fun mergeActivity(
    existing: List<ActivityItemDto>,
    next: List<ActivityItemDto>,
): List<ActivityItemDto> {
    val seen = existing.mapTo(HashSet()) { it.id }
    return existing + next.filter { seen.add(it.id) }
}

// --- Timeline: days and runs -------------------------------------------------------------------

/** One day of the timeline: its label and its rows, newest first. */
data class ActivityDay(val label: DayLabel, val rows: List<ActivityRowModel>)

private fun ActivityItemDto.sameActor(other: ActivityItemDto): Boolean =
    actor.isSelf == other.actor.isSelf &&
        actor.kind == other.actor.kind &&
        actor.name == other.actor.name

/** One-off facts (lifecycle, sharing, timer) are single events and never fold into a run. */
private val standaloneActions =
    setOf(
        "created",
        "deleted",
        "trashed",
        "restored",
        "purged",
        "step_trashed",
        "step_restored",
        "step_purged",
        "shared",
        "share_role_changed",
        "share_revoked",
        "share_left",
        "ownership_transferred",
        "timer_started",
        "timer_stopped",
        "timer_total_changed",
    )

private fun ActivityItemDto.foldsInto(newer: ActivityItemDto): Boolean =
    entityType == newer.entityType &&
        entityId == newer.entityId &&
        action == newer.action &&
        action !in standaloneActions &&
        sameActor(newer)

/**
 * Folds neighbouring entries (newest first) for the same item, action and actor into one: counts
 * add up, the run's start moves back and the changed fields are united. The server already does
 * this within 10 minutes; this extends it to a whole day. Pass the entries of a single day.
 */
fun mergeActivityRuns(items: List<ActivityItemDto>): List<ActivityItemDto> {
    val merged = ArrayList<ActivityItemDto>()
    for (item in items) {
        val last = merged.lastOrNull()
        if (last != null && item.foldsInto(last)) {
            merged[merged.lastIndex] =
                last.copy(
                    count = last.count.coerceAtLeast(1) + item.count.coerceAtLeast(1),
                    firstEventId = item.firstEventId,
                    firstCreatedAt = item.firstCreatedAt ?: item.createdAt,
                    thumbnailUrl = last.thumbnailUrl ?: item.thumbnailUrl,
                    changedFields = (last.changedFields + item.changedFields).distinct(),
                )
        } else {
            merged += item
        }
    }
    return merged
}

/** Groups a newest-first feed into consecutive local days and folds repeats within each day. */
fun groupActivity(items: List<ActivityItemDto>, today: LocalDate, zone: ZoneId): List<ActivityDay> {
    val days = ArrayList<ActivityDay>()
    var label: DayLabel? = null
    var bucket = ArrayList<ActivityItemDto>()
    fun flush() {
        val current = label
        if (current != null && bucket.isNotEmpty()) {
            days += ActivityDay(current, mergeActivityRuns(bucket).map(::activityRow))
        }
        bucket = ArrayList()
    }
    for (item in items) {
        val itemLabel = dayLabelFor(activityInstant(item.createdAt), today, zone)
        if (itemLabel != label) {
            flush()
            label = itemLabel
        }
        bucket += item
    }
    flush()
    return days
}

fun activityInstant(createdAt: String): Instant? =
    runCatching { Instant.ofEpochMilli(DateTimeUtils.parseToEpochMillis(createdAt)) }.getOrNull()

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** The time of day in [zone], 24-hour like the web ("14:32"); null when unreadable. */
fun activityTimeOfDay(createdAt: String, zone: ZoneId): String? =
    activityInstant(createdAt)?.atZone(zone)?.format(timeFormat)
