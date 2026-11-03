package blue.anika.wolle.ui.history

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditEventDto
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
