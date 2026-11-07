package blue.anika.wolle.ui.history

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditMediaDto
import blue.anika.wolle.data.api.dto.ChangeItemDto
import blue.anika.wolle.data.api.dto.ChangeSetDto
import blue.anika.wolle.data.api.dto.ChangeValue
import blue.anika.wolle.data.api.dto.parseChangeValue

/*
 * Pure rules behind the "Recent changes" section, mirroring `history-summary.ts` on the web: what
 * a change set says in one line, which events are on screen, and who gets named.
 */

/** How many events the collapsed section shows. */
const val HISTORY_PREVIEW_COUNT = 3

enum class HistoryCountKind {
    Photo,
    Step,
    Attachment,
}

/** One piece of the human summary of an event. */
sealed interface SummaryPart {
    /** "Changed name and notes": [nounRes] are string resources of the field nouns, no repeats. */
    data class ChangedFields(val nounRes: List<Int>) : SummaryPart

    /** "Added 2 photos" (positive) or "Removed a step" (negative). */
    data class CountChange(val kind: HistoryCountKind, val delta: Int) : SummaryPart
}

private val fieldNouns: Map<String, Int> =
    mapOf(
        "project.name" to R.string.history_noun_name,
        "project.category" to R.string.history_noun_category,
        "project.yarn" to R.string.history_noun_yarn,
        "project.yarns" to R.string.history_noun_linked_yarns,
        "project.needles" to R.string.history_noun_needles,
        "project.stitch_sample" to R.string.history_noun_stitch_sample,
        "project.description" to R.string.history_noun_description,
        "project.notes" to R.string.history_noun_notes,
        "project.other_materials" to R.string.history_noun_other_materials,
        "project.tags" to R.string.history_noun_tags,
        "project.steps" to R.string.history_noun_steps,
        "project.is_ai_enhanced" to R.string.history_noun_ai,
        "project.link" to R.string.history_noun_link,
        "project.cover_image" to R.string.history_noun_cover_image,
        "project.image_order" to R.string.history_noun_image_order,
        "project.step_image_order" to R.string.history_noun_step_image_order,
        "yarn.name" to R.string.history_noun_name,
        "yarn.description" to R.string.history_noun_description,
        "yarn.brand" to R.string.history_noun_brand,
        "yarn.colorway" to R.string.history_noun_colorway,
        "yarn.dye_lot" to R.string.history_noun_dye_lot,
        "yarn.fiber_content" to R.string.history_noun_fiber_content,
        "yarn.weight_category" to R.string.history_noun_yarn_weight,
        "yarn.recommended_needles" to R.string.history_noun_recommended_needles,
        "yarn.weight_grams" to R.string.history_noun_skein_weight,
        "yarn.length_meters" to R.string.history_noun_skein_length,
        "yarn.notes" to R.string.history_noun_notes,
        "yarn.is_ai_enhanced" to R.string.history_noun_ai,
        "yarn.link" to R.string.history_noun_link,
        "yarn.primary_photo" to R.string.history_noun_primary_photo,
        "yarn.photo_order" to R.string.history_noun_photo_order,
        "category.name" to R.string.history_noun_name,
    )

/** The string resource of the noun for a change-field key such as `project.name`, or null. */
fun fieldNounRes(field: String): Int? = fieldNouns[field]

private val countFields: Map<String, HistoryCountKind> =
    mapOf(
        "project.image_count" to HistoryCountKind.Photo,
        "yarn.photo_count" to HistoryCountKind.Photo,
        "project.step_count" to HistoryCountKind.Step,
        "project.attachment_count" to HistoryCountKind.Attachment,
    )

private fun ChangeItemDto.countDelta(): Int? {
    val before = parseChangeValue(before) as? ChangeValue.Integer ?: return null
    val after = parseChangeValue(after) as? ChangeValue.Integer ?: return null
    return (after.value ?: 0) - (before.value ?: 0)
}

/**
 * What a change set did, as summary parts: the count changes first would read oddly, so the
 * "Changed ..." part comes first, then "Added 2 photos" and the like. Empty when nothing is
 * summarisable (the caller then shows the action name).
 */
fun summaryParts(changes: ChangeSetDto?): List<SummaryPart> {
    if (changes == null || changes.items.isEmpty()) return emptyList()
    val counts = mutableListOf<SummaryPart.CountChange>()
    val countedKinds = mutableSetOf<HistoryCountKind>()
    for (item in changes.items) {
        val kind = countFields[item.field] ?: continue
        val delta = item.countDelta() ?: continue
        if (delta != 0) {
            counts += SummaryPart.CountChange(kind, delta)
            countedKinds += kind
        }
    }
    val nouns = mutableListOf<Int>()
    for (item in changes.items) {
        if (item.field in countFields) continue
        if (item.field == "project.steps" && HistoryCountKind.Step in countedKinds) continue
        val noun = fieldNouns[item.field] ?: continue
        if (noun !in nouns) nouns += noun
    }
    return buildList {
        if (nouns.isNotEmpty()) add(SummaryPart.ChangedFields(nouns))
        addAll(counts)
    }
}

/** "a", "a and b", "a, b and c" (the localized [and] word is passed in). */
fun joinNatural(items: List<String>, and: String): String =
    when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " $and " + items.last()
    }

/** The events on screen: the newest [HISTORY_PREVIEW_COUNT] until the user asks for all. */
fun visibleEvents(
    events: List<AuditEventDto>,
    showAll: Boolean,
    preview: Int = HISTORY_PREVIEW_COUNT,
): List<AuditEventDto> = if (showAll) events else events.take(preview)

/** Whether the "Show all" control is worth offering. */
fun canShowAll(
    events: List<AuditEventDto>,
    hasMore: Boolean,
    preview: Int = HISTORY_PREVIEW_COUNT,
): Boolean = events.size > preview || hasMore

/** The author to name on a row: nobody when it is the signed-in user (or unknown). */
fun otherActor(actorEmail: String?, currentEmail: String?): String? = actorEmail?.takeIf {
    it.isNotBlank() && !it.equals(currentEmail, ignoreCase = true)
}

/** Appends a page, dropping events already present (a new edit can shift page boundaries). */
fun mergeEvents(existing: List<AuditEventDto>, next: List<AuditEventDto>): List<AuditEventDto> {
    val seen = existing.mapTo(HashSet()) { it.id }
    return existing + next.filter { seen.add(it.id) }
}

/** What the details sheet of one history entry shows, mirroring `HistoryEventDetail.svelte`. */
sealed interface HistoryDetailBody {
    /** An upload or removal: the picture or file (owner-scoped URLs from the history API). */
    data class Media(val media: AuditMediaDto) : HistoryDetailBody

    /** Nothing was recorded for this event: "No details recorded". */
    data object NoDetails : HistoryDetailBody

    data object Loading : HistoryDetailBody

    data object Failed : HistoryDetailBody

    /** Recorded field changes; an empty list reads "No changes". */
    data class Changes(val items: List<ChangeItemDto>) : HistoryDetailBody

    /**
     * The snapshot cannot be shown (`too_large`, `malformed` or unknown; `not_recorded` is
     * [NoDetails]).
     */
    data class Unavailable(val reason: String?) : HistoryDetailBody
}

/**
 * Maps a history entry and its (possibly not yet loaded) detail to what its sheet shows. Every
 * entry is openable: one with no media and no recorded changes says so without a request.
 */
fun historyDetailBody(event: AuditEventDto, detail: HistoryDetailState?): HistoryDetailBody {
    event.media?.let {
        return HistoryDetailBody.Media(it)
    }
    if (!event.hasChanges) return HistoryDetailBody.NoDetails
    return when (detail) {
        null,
        HistoryDetailState.Loading -> HistoryDetailBody.Loading
        HistoryDetailState.Failed -> HistoryDetailBody.Failed
        is HistoryDetailState.Loaded ->
            detail.detail.changes?.let { HistoryDetailBody.Changes(it.items) }
                ?: if (detail.detail.unavailableReason == "not_recorded")
                    HistoryDetailBody.NoDetails
                else HistoryDetailBody.Unavailable(detail.detail.unavailableReason)
    }
}

/**
 * One line of a sharing event, for the history of a project or yarn: which sentence, and who and
 * what role it names. [name] is null when that account was deleted ("a deleted account").
 */
data class SharingEventLine(
    @androidx.annotation.StringRes val textRes: Int,
    val name: String?,
    /** `viewer` or `editor` when the sentence names the role, else null. */
    val role: String?,
)

/** The sentence for a sharing action with its [subject], or null for any other action. */
fun sharingEventLine(
    action: String,
    subject: blue.anika.wolle.data.api.dto.AuditSubjectDto?,
): SharingEventLine? {
    val name = subject?.name?.takeIf { it.isNotBlank() }
    val role = subject?.role
    val res =
        when (action) {
            "shared" -> R.string.history_shared
            "share_role_changed" -> R.string.history_share_role_changed
            "share_revoked" -> R.string.history_share_revoked
            "share_left" -> R.string.history_share_left
            "ownership_transferred" -> R.string.history_ownership_transferred
            else -> return null
        }
    return SharingEventLine(res, name, role)
}

/** Change fields whose values are picture file labels (an order, or one cover/primary photo). */
private val mediaLabelFields =
    setOf(
        "project.image_order",
        "project.step_image_order",
        "yarn.photo_order",
        "project.cover_image",
        "yarn.primary_photo",
    )

/** How a picture differs between the before and after side of a change. */
enum class MediaMark {
    Kept,
    /** Only in the after list. */
    Added,
    /** Only in the before list. */
    Removed,
    /** In both lists, but at a different place among the pictures both lists share. */
    Moved,
}

/** One picture (or label without a known picture) of a before/after row. */
data class MediaThumb(val label: String, val mark: MediaMark)

/** The two rows of a picture change: what it was and what it is now. */
data class MediaChangeRows(val before: List<MediaThumb>, val after: List<MediaThumb>)

private fun ChangeValue.mediaLabels(): List<String> =
    when (this) {
        is ChangeValue.TextList -> values
        is ChangeValue.Text -> listOfNotNull(value?.takeIf { it.isNotBlank() })
        else -> emptyList()
    }

/**
 * The labels that did not move: the longest run of shared pictures that keeps its relative order
 * ([beforeOrder] and [afterOrder] hold the same labels). Everything else was moved, so moving one
 * picture to the front marks that picture and not all the others.
 */
fun unmovedLabels(beforeOrder: List<String>, afterOrder: List<String>): Set<String> {
    val positions = beforeOrder.map { afterOrder.indexOf(it) }
    val length = IntArray(positions.size) { 1 }
    val previous = IntArray(positions.size) { -1 }
    var best = -1
    for (i in positions.indices) {
        for (j in 0 until i) {
            if (positions[j] < positions[i] && length[j] + 1 > length[i]) {
                length[i] = length[j] + 1
                previous[i] = j
            }
        }
        if (best < 0 || length[i] > length[best]) best = i
    }
    val kept = mutableSetOf<String>()
    var at = best
    while (at >= 0) {
        kept += beforeOrder[at]
        at = previous[at]
    }
    return kept
}

/**
 * The thumbnail rows of a picture change, or null when it keeps the text rendering: not a picture
 * field, or none of its labels has a known picture in [media] (old server, deleted photos).
 */
fun mediaChangeRows(
    item: ChangeItemDto,
    media: Map<String, blue.anika.wolle.data.api.dto.HistoryMediaDto>,
): MediaChangeRows? {
    if (item.field !in mediaLabelFields || media.isEmpty()) return null
    val before = parseChangeValue(item.before).mediaLabels()
    val after = parseChangeValue(item.after).mediaLabels()
    if ((before + after).none { it in media }) return null
    val kept = unmovedLabels(before.filter { it in after }, after.filter { it in before })
    return MediaChangeRows(
        before =
            before.map { label ->
                MediaThumb(
                    label,
                    when {
                        label !in after -> MediaMark.Removed
                        label !in kept -> MediaMark.Moved
                        else -> MediaMark.Kept
                    },
                )
            },
        after =
            after.map { label ->
                MediaThumb(
                    label,
                    when {
                        label !in before -> MediaMark.Added
                        label !in kept -> MediaMark.Moved
                        else -> MediaMark.Kept
                    },
                )
            },
    )
}
