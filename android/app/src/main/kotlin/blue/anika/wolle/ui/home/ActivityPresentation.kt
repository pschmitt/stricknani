package blue.anika.wolle.ui.home

import blue.anika.wolle.data.api.dto.ActivityItemDto
import blue.anika.wolle.ui.history.fieldNounRes

/*
 * Pure rules behind Home's "Recent activity": which sentence a feed item gets, how many changed
 * fields are named and what the row does when tapped. The Compose layer only turns this into text.
 */

/** How many rows the section shows at first, and how many each "Show more" adds. */
const val ACTIVITY_PAGE_SIZE = 6

/** At most this many field labels are named on a row; the rest become "N more". */
const val ACTIVITY_MAX_FIELDS = 3

enum class ActivityVerb {
    Created,
    Updated,
    Deleted,
    Uploaded,
    Removed,
    /** The legacy imported actions (`step_updated` and the like) and anything unknown. */
    Changed,
}

fun activityVerb(action: String): ActivityVerb =
    when (action.lowercase()) {
        "created" -> ActivityVerb.Created
        "updated" -> ActivityVerb.Updated
        "deleted" -> ActivityVerb.Deleted
        "uploaded" -> ActivityVerb.Uploaded
        "removed" -> ActivityVerb.Removed
        else -> ActivityVerb.Changed
    }

/** Where a tap on a row leads. */
sealed interface ActivityTarget {
    data class Project(val id: Int) : ActivityTarget

    data class Yarn(val id: Int) : ActivityTarget
}

/**
 * One row, ready to draw. [actorName] is null for "You" (the signed-in user) and for an actor the
 * server does not name; [actorIsSelf] tells those apart.
 */
data class ActivityRowModel(
    val key: Long,
    val verb: ActivityVerb,
    val actorIsSelf: Boolean,
    val actorName: String?,
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
    /** A deleted resource: muted and not tappable. */
    val deleted: Boolean
        get() = target == null
}

/** The row for a feed item. */
fun activityRow(item: ActivityItemDto): ActivityRowModel {
    val isYarn = item.entityType == "yarn"
    val nouns = item.changedFields.mapNotNull(::fieldNounRes).distinct()
    val target =
        if (!item.exists) {
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
        verb = activityVerb(item.action),
        actorIsSelf = item.actor.isSelf,
        actorName = item.actor.name?.takeIf { it.isNotBlank() && !item.actor.isSelf },
        isYarn = isYarn,
        entityName = item.entityName?.takeIf { it.isNotBlank() },
        count = item.count.coerceAtLeast(1),
        fieldNounRes = nouns.take(ACTIVITY_MAX_FIELDS),
        moreFields = (nouns.size - ACTIVITY_MAX_FIELDS).coerceAtLeast(0),
        thumbnailUrl = item.thumbnailUrl.takeIf { item.exists },
        target = target,
        createdAt = item.createdAt,
    )
}

/**
 * The range of [name] inside the formatted [sentence] (to draw it bold), or null when it is not in
 * there. The last occurrence is used: the name follows the actor in every sentence, and an actor
 * called like the resource must not take the emphasis.
 */
fun nameRange(sentence: String, name: String?): IntRange? {
    if (name.isNullOrEmpty()) return null
    val start = sentence.lastIndexOf(name)
    return if (start < 0) null else start until start + name.length
}

/** Appends a page, dropping items already present (a new edit can shift the page boundary). */
fun mergeActivity(
    existing: List<ActivityItemDto>,
    next: List<ActivityItemDto>,
): List<ActivityItemDto> {
    val seen = existing.mapTo(HashSet()) { it.id }
    return existing + next.filter { seen.add(it.id) }
}
