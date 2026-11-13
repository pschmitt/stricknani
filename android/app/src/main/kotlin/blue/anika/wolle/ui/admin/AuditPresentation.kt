package blue.anika.wolle.ui.admin

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditClientDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditMediaDto
import blue.anika.wolle.data.api.dto.AuditResourceDto
import blue.anika.wolle.data.api.dto.ChangeStep
import blue.anika.wolle.data.api.dto.ChangeValue
import blue.anika.wolle.data.api.dto.RESOURCE_STATE_ACTIVE
import blue.anika.wolle.data.api.dto.RESOURCE_STATE_GONE
import blue.anika.wolle.data.api.dto.RESOURCE_STATE_IN_TRASH
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.UserKind
import blue.anika.wolle.ui.common.UserRef
import blue.anika.wolle.ui.history.TIMER_SECONDS_FIELD
import blue.anika.wolle.ui.history.formatTimerDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Pure presentation rules for the audit log, mirroring `web/src/lib/features/admin/audit-action.ts`
 * and `DiffPanel.svelte`. Kept free of Compose state so they can be unit tested.
 */

/**
 * The five visual kinds of audit action (the web's `action-*` icons), administrator impersonation,
 * and "anything else".
 */
enum class AuditActionKind {
    Create,
    Update,
    Delete,
    Upload,
    Remove,
    /** A share stopped or was left: not a deletion, so it stays neutral (web `share_revoked`). */
    Unshare,
    /** Restored from the trash. */
    Restore,
    Impersonate,
    Other,
}

/** The colour family of an action. Resolved to real colours in the UI (see `toneColor`). */
enum class AuditTone {
    Green,
    Blue,
    Red,
    Marigold,
    Muted,
}

data class AuditActionPresentation(
    val kind: AuditActionKind,
    /** Known actions have a translated label; unknown ones use [fallbackLabel]. */
    @StringRes val labelRes: Int?,
    val fallbackLabel: String,
)

private val knownActions: Map<String, Pair<AuditActionKind, Int>> =
    mapOf(
        "created" to (AuditActionKind.Create to R.string.admin_action_created),
        "updated" to (AuditActionKind.Update to R.string.admin_action_updated),
        "deleted" to (AuditActionKind.Delete to R.string.admin_action_deleted),
        "trashed" to (AuditActionKind.Delete to R.string.admin_action_trashed),
        "restored" to (AuditActionKind.Restore to R.string.admin_action_restored),
        "purged" to (AuditActionKind.Delete to R.string.admin_action_purged),
        "step_trashed" to (AuditActionKind.Delete to R.string.admin_action_step_trashed),
        "step_restored" to (AuditActionKind.Restore to R.string.admin_action_step_restored),
        "step_purged" to (AuditActionKind.Delete to R.string.admin_action_step_purged),
        "uploaded" to (AuditActionKind.Upload to R.string.admin_action_uploaded),
        "removed" to (AuditActionKind.Remove to R.string.admin_action_removed),
        "step_created" to (AuditActionKind.Create to R.string.admin_action_step_created),
        "step_updated" to (AuditActionKind.Update to R.string.admin_action_step_updated),
        "step_deleted" to (AuditActionKind.Delete to R.string.admin_action_step_deleted),
        "title_image_uploaded" to
            (AuditActionKind.Upload to R.string.admin_action_title_image_uploaded),
        "stitch_sample_image_uploaded" to
            (AuditActionKind.Upload to R.string.admin_action_stitch_sample_image_uploaded),
        "step_image_uploaded" to
            (AuditActionKind.Upload to R.string.admin_action_step_image_uploaded),
        "attachment_uploaded" to
            (AuditActionKind.Upload to R.string.admin_action_attachment_uploaded),
        "attachment_deleted" to
            (AuditActionKind.Delete to R.string.admin_action_attachment_deleted),
        "image_deleted" to (AuditActionKind.Delete to R.string.admin_action_image_deleted),
        "photo_uploaded" to (AuditActionKind.Upload to R.string.admin_action_photo_uploaded),
        "photo_deleted" to (AuditActionKind.Delete to R.string.admin_action_photo_deleted),
        "timer_started" to (AuditActionKind.Create to R.string.admin_action_timer_started),
        "timer_stopped" to (AuditActionKind.Update to R.string.admin_action_timer_stopped),
        "timer_total_changed" to
            (AuditActionKind.Update to R.string.admin_action_timer_total_changed),
        "photo_promoted" to (AuditActionKind.Update to R.string.admin_action_photo_promoted),
        "title_image_promoted" to
            (AuditActionKind.Update to R.string.admin_action_title_image_promoted),
        "shared" to (AuditActionKind.Create to R.string.admin_action_shared),
        "share_role_changed" to
            (AuditActionKind.Update to R.string.admin_action_share_role_changed),
        "share_revoked" to (AuditActionKind.Unshare to R.string.admin_action_share_revoked),
        "share_left" to (AuditActionKind.Unshare to R.string.admin_action_share_left),
        "ownership_transferred" to
            (AuditActionKind.Update to R.string.admin_action_ownership_transferred),
        "backup_restored" to (AuditActionKind.Restore to R.string.admin_action_backup_restored),
        "impersonation_started" to
            (AuditActionKind.Impersonate to R.string.admin_action_impersonation_started),
        "impersonation_stopped" to
            (AuditActionKind.Impersonate to R.string.admin_action_impersonation_stopped),
    )

/** Action names the filter offers as suggestions (the server filters on the exact name). */
val knownAuditActionNames: List<String> = knownActions.keys.toList()

/**
 * The label and icon of an action. [system] marks the automatic purge after 90 days, which reads
 * "Purged automatically after 90 days" (a manual purge and legacy `deleted` rows say "Deleted
 * permanently").
 */
fun auditActionPresentation(action: String, system: Boolean = false): AuditActionPresentation {
    if (action == "purged" && system) {
        return AuditActionPresentation(
            AuditActionKind.Delete,
            R.string.admin_action_purged_auto,
            action,
        )
    }
    val known = knownActions[action]
    return if (known != null) {
        AuditActionPresentation(known.first, known.second, action)
    } else {
        AuditActionPresentation(AuditActionKind.Other, null, prettifyAction(action))
    }
}

/** `some_new_action` -> `Some new action`, for actions this app version has no label for. */
internal fun prettifyAction(action: String): String =
    action.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

fun AuditActionKind.icon(): ImageVector =
    when (this) {
        AuditActionKind.Create -> Icons.Filled.Add
        AuditActionKind.Update -> Icons.Filled.Edit
        AuditActionKind.Delete -> PhosphorIcons.Trash
        AuditActionKind.Upload -> Icons.Filled.CloudUpload
        AuditActionKind.Remove,
        AuditActionKind.Unshare -> Icons.Filled.RemoveCircle
        AuditActionKind.Restore -> Icons.AutoMirrored.Filled.Undo
        AuditActionKind.Impersonate -> PhosphorIcons.UserCircle
        AuditActionKind.Other -> PhosphorIcons.ClockCounterClockwise
    }

/**
 * create/restore: green, update: blue, upload: marigold, anything else (impersonation, stopped
 * shares): muted. Red is reserved for deletion: delete and remove. Same rules as the web's
 * `auditActionTone`.
 */
fun AuditActionKind.tone(): AuditTone =
    when (this) {
        AuditActionKind.Create,
        AuditActionKind.Restore -> AuditTone.Green
        AuditActionKind.Update -> AuditTone.Blue
        AuditActionKind.Delete,
        AuditActionKind.Remove -> AuditTone.Red
        AuditActionKind.Upload -> AuditTone.Marigold
        AuditActionKind.Unshare,
        AuditActionKind.Impersonate,
        AuditActionKind.Other -> AuditTone.Muted
    }

/** Updates are blue, not red-ish, so they never read like deletions. */
val UpdateBlueLight = Color(0xFF1D5FB8)
val UpdateBlueDark = Color(0xFFA8C8FF)
val AddedGreenLight = Color(0xFF1B6E3A)
val AddedGreenDark = Color(0xFF8FD9A6)

/** The entity filter values the API accepts besides "all". */
val auditEntityTypes: List<String> = listOf("project", "yarn", "category", "server")

@StringRes
fun entityTypeLabelRes(entityType: String): Int? =
    when (entityType) {
        "project" -> R.string.admin_entity_project
        "yarn" -> R.string.admin_entity_yarn
        "category" -> R.string.admin_entity_category
        "server" -> R.string.admin_entity_server
        else -> null
    }

/** What an event was about: the resource name the server resolved, if any. */
fun AuditEventDto.resourceName(): String? = resource.name?.trim()?.ifBlank { null }

/** Two-letter-free initial for an avatar fallback: first letter of the email, or "?". */
fun initialOf(email: String?): String =
    email?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

// --- Day grouping ----------------------------------------------------------------------------

sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class On(val date: LocalDate) : DayLabel

    /** Timestamp the app could not parse. */
    data object Unknown : DayLabel
}

data class AuditDayGroup(val label: DayLabel, val events: List<AuditEventDto>)

fun auditEventInstant(event: AuditEventDto): Instant? =
    runCatching { Instant.ofEpochMilli(DateTimeUtils.parseToEpochMillis(event.createdAt)) }
        .getOrNull()

/**
 * Groups events, in the order given (the server sends newest first), into consecutive days in
 * [zone]. Events from the same day that are not adjacent start a new group rather than being
 * reordered.
 */
fun groupEventsByDay(
    events: List<AuditEventDto>,
    today: LocalDate,
    zone: ZoneId,
): List<AuditDayGroup> {
    val groups = mutableListOf<AuditDayGroup>()
    var currentLabel: DayLabel? = null
    var bucket = mutableListOf<AuditEventDto>()
    for (event in events) {
        val label = dayLabelFor(auditEventInstant(event), today, zone)
        if (label != currentLabel && bucket.isNotEmpty()) {
            groups += AuditDayGroup(currentLabel!!, bucket)
            bucket = mutableListOf()
        }
        currentLabel = label
        bucket += event
    }
    if (bucket.isNotEmpty()) groups += AuditDayGroup(currentLabel!!, bucket)
    return groups
}

fun dayLabelFor(instant: Instant?, today: LocalDate, zone: ZoneId): DayLabel {
    if (instant == null) return DayLabel.Unknown
    val date = instant.atZone(zone).toLocalDate()
    return when (date) {
        today -> DayLabel.Today
        today.minusDays(1) -> DayLabel.Yesterday
        else -> DayLabel.On(date)
    }
}

// --- Change fields and values ----------------------------------------------------------------

private val fieldLabels: Map<String, Int> =
    mapOf(
        "project.name" to R.string.admin_field_project_name,
        "project.category" to R.string.admin_field_category,
        "project.yarn" to R.string.admin_field_yarn,
        "project.yarns" to R.string.admin_field_linked_yarns,
        "project.needles" to R.string.admin_field_needles,
        "project.stitch_sample" to R.string.admin_field_stitch_sample,
        "project.description" to R.string.admin_field_description,
        "project.notes" to R.string.admin_field_notes,
        "project.other_materials" to R.string.admin_field_other_materials,
        "project.tags" to R.string.admin_field_tags,
        "project.steps" to R.string.admin_field_steps,
        "project.step_count" to R.string.admin_field_step_count,
        "project.image_count" to R.string.admin_field_image_count,
        "project.attachment_count" to R.string.admin_field_attachment_count,
        "project.is_ai_enhanced" to R.string.admin_field_ai_enhanced,
        "project.link" to R.string.admin_field_link,
        "project.cover_image" to R.string.admin_field_cover_image,
        "project.image_order" to R.string.admin_field_image_order,
        "project.step_image_order" to R.string.admin_field_step_image_order,
        TIMER_SECONDS_FIELD to R.string.admin_field_timer_seconds,
        "yarn.name" to R.string.admin_field_yarn_name,
        "yarn.description" to R.string.admin_field_description,
        "yarn.brand" to R.string.admin_field_brand,
        "yarn.colorway" to R.string.admin_field_colorway,
        "yarn.dye_lot" to R.string.admin_field_dye_lot,
        "yarn.fiber_content" to R.string.admin_field_fiber_content,
        "yarn.weight_category" to R.string.admin_field_weight_category,
        "yarn.recommended_needles" to R.string.admin_field_recommended_needles,
        "yarn.weight_grams" to R.string.admin_field_weight,
        "yarn.length_meters" to R.string.admin_field_length,
        "yarn.notes" to R.string.admin_field_notes,
        "yarn.photo_count" to R.string.admin_field_photo_count,
        "yarn.is_ai_enhanced" to R.string.admin_field_ai_enhanced,
        "yarn.link" to R.string.admin_field_link,
        "yarn.primary_photo" to R.string.admin_field_primary_photo,
        "yarn.photo_order" to R.string.admin_field_photo_order,
        "category.name" to R.string.admin_field_category_name,
    )

/** Translated label for a change field, or null when this app version does not know it. */
@StringRes fun changeFieldLabelRes(field: String): Int? = fieldLabels[field]

/** How one side of a change is shown, as lines of plain text. */
fun ChangeValue.displayText(string: (Int) -> String, field: String? = null): String =
    when (this) {
        is ChangeValue.Text -> value?.ifBlank { null } ?: string(R.string.admin_value_not_provided)
        is ChangeValue.TextList ->
            if (values.isEmpty()) string(R.string.admin_value_empty_list)
            else values.joinToString("\n")
        is ChangeValue.Yarns ->
            if (names.isEmpty()) string(R.string.admin_value_empty_list)
            else names.joinToString("\n")
        is ChangeValue.Integer ->
            value?.let {
                if (field == TIMER_SECONDS_FIELD) formatTimerDuration(it.toLong()) else "$it"
            } ?: string(R.string.admin_value_not_provided)
        is ChangeValue.Flag ->
            string(if (value) R.string.admin_value_yes else R.string.admin_value_no)
        is ChangeValue.Steps ->
            if (steps.isEmpty()) string(R.string.admin_value_empty_list)
            else steps.joinToString("\n\n") { it.asLine() }
        is ChangeValue.LinkChanged ->
            string(
                if (present) R.string.admin_value_link_present else R.string.admin_value_link_absent
            )
        is ChangeValue.LegacyText -> value
        ChangeValue.Unknown -> string(R.string.admin_value_unknown)
    }

/** True when this side has no real value, so the UI shows a muted placeholder instead of text. */
fun ChangeValue.isEmptyValue(): Boolean =
    when (this) {
        is ChangeValue.Text -> value.isNullOrBlank()
        is ChangeValue.TextList -> values.isEmpty()
        is ChangeValue.Yarns -> names.isEmpty()
        is ChangeValue.Integer -> value == null
        is ChangeValue.Steps -> steps.isEmpty()
        ChangeValue.Unknown -> true
        else -> false
    }

private fun ChangeStep.asLine(): String = "$number. $title" + (description?.let { "\n$it" } ?: "")

/** `unavailable_reason` -> message. */
@StringRes
fun unavailableReasonRes(reason: String?): Int =
    when (reason) {
        "not_recorded" -> R.string.admin_changes_not_recorded
        "malformed" -> R.string.admin_changes_malformed
        "too_large" -> R.string.admin_changes_too_large
        else -> R.string.admin_changes_unavailable
    }

// --- What an event offers: its resource, its media, its details --------------------------------

/** What became of the item an event was about: still there, in the trash, or gone for good. */
sealed interface AuditItemState {
    data object Active : AuditItemState

    /** In the trash for [daysRemaining] more days; [canRestore] says whether the viewer may. */
    data class InTrash(val daysRemaining: Int, val canRestore: Boolean) : AuditItemState

    data object Gone : AuditItemState
}

/**
 * The state of an event's resource; servers without the trash only say [AuditResourceDto.exists].
 */
fun AuditResourceDto.itemState(): AuditItemState =
    when (state) {
        RESOURCE_STATE_IN_TRASH ->
            AuditItemState.InTrash(trash?.daysRemaining ?: 0, trash?.canRestore ?: false)
        RESOURCE_STATE_GONE -> AuditItemState.Gone
        RESOURCE_STATE_ACTIVE -> AuditItemState.Active
        else -> if (exists) AuditItemState.Active else AuditItemState.Gone
    }

/** Actions of the trash lifecycle (and the legacy hard delete): they name the owner. */
private val lifecycleActions =
    setOf(
        "trashed",
        "restored",
        "purged",
        "deleted",
        "step_trashed",
        "step_restored",
        "step_purged",
    )

/**
 * The owner of the item for an event an administrator did (or the automatic purge did): "Owner:
 * Anika". Null for everything else, and when that account is gone.
 */
fun AuditEventDto.lifecycleOwnerName(): String? =
    if (action in lifecycleActions) subject?.name?.trim()?.ifBlank { null } else null

/** [lifecycleOwnerName] as the person to show with the shared UserLabel. */
fun AuditEventDto.lifecycleOwnerRef(): UserRef? =
    lifecycleOwnerName()?.let { UserRef(UserKind.User, it, subject?.avatarUrl) }

/**
 * Whether [AuditResourceDto.state] describes what the event is about. For a file event (it has
 * `media`) and a step event (`step_*`) it describes the parent project or yarn, not the file or
 * step, so no state line and no Restore button: the Trash screen is where those come back.
 */
fun AuditEventDto.stateDescribesItem(): Boolean = media == null && !action.startsWith("step_")

/** The in-trash state to show for an event, or null when it does not describe the item. */
fun AuditEventDto.inTrashState(): AuditItemState.InTrash? =
    if (stateDescribesItem()) resource.itemState() as? AuditItemState.InTrash else null

/** The item an event can restore: in the trash and restorable by the viewer. */
fun AuditEventDto.restoreRef(): TrashRef? {
    val state = inTrashState()
    if (state == null || !state.canRestore || entityId <= 0) return null
    return TrashRef(resource.kind ?: entityType, entityId)
}

/** Where tapping an event's item should lead. */
sealed interface AuditTarget {
    /** The normal project page (the server grants administrators every project). */
    data class Project(val id: Long) : AuditTarget

    data class Yarn(val id: Long) : AuditTarget

    /**
     * The item is in the trash: the normal page would answer 404 and there is no read-only item
     * view, so this leads to the Trash screen (restorable when [canRestore]).
     */
    data class InTrash(val canRestore: Boolean) : AuditTarget

    /** The project or yarn no longer exists: show a "deleted" state, not a dead link. */
    data object Deleted : AuditTarget

    /** Categories and unknown resources have no page of their own. */
    data object None : AuditTarget
}

fun AuditEventDto.target(): AuditTarget {
    val kind = resource.kind ?: entityType
    (resource.itemState() as? AuditItemState.InTrash)?.let {
        return AuditTarget.InTrash(it.canRestore && stateDescribesItem())
    }
    if (kind != "project" && kind != "yarn") return AuditTarget.None
    if (!resource.exists || entityId <= 0) return AuditTarget.Deleted
    return if (kind == "project") AuditTarget.Project(entityId) else AuditTarget.Yarn(entityId)
}

/** What the media of an upload/removal event can show right now. */
sealed interface AuditMediaState {
    /** The picture still exists: show it (thumbnail in lists, full size in the sheet). */
    data class Picture(val url: String, val thumbnailUrl: String?) : AuditMediaState

    /** A non-image file that still exists: offer it to open. */
    data class File(val url: String) : AuditMediaState

    /** The file was deleted since; only its name and kind are known. */
    data class Deleted(val isImage: Boolean) : AuditMediaState
}

fun AuditMediaDto.state(): AuditMediaState {
    val link = url
    return when {
        !exists || link.isNullOrBlank() -> AuditMediaState.Deleted(isImage)
        isImage -> AuditMediaState.Picture(link, thumbnailUrl)
        else -> AuditMediaState.File(link)
    }
}

/** The file name to show, falling back to the kind when the server never recorded one. */
fun AuditMediaDto.displayName(): String? = name?.trim()?.ifBlank { null }

/** What the detail sheet of an event can show. */
enum class AuditDetailKind {
    /** An upload or removal: the picture or file. */
    Media,

    /** Recorded field changes (loaded on demand). */
    Changes,

    /** Nothing was recorded (old events). */
    None,
}

fun AuditEventDto.detailKind(): AuditDetailKind =
    when {
        media != null -> AuditDetailKind.Media
        hasChanges -> AuditDetailKind.Changes
        else -> AuditDetailKind.None
    }

/** A file name that is safe to create in the cache directory. */
fun safeAuditFileName(media: AuditMediaDto): String {
    val base =
        (media.displayName() ?: "file")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .ifEmpty { "file" }
            .take(80)
    return "${media.id}-$base"
}

// --- Client of an event -----------------------------------------------------------------------

enum class AuditClientKind {
    Web,
    Android,
    Api,
    System,
    Unknown,
}

/** Events without a client block, or with a kind this app does not know, read as unknown. */
fun AuditClientDto?.clientKind(): AuditClientKind =
    when (this?.kind) {
        "web" -> AuditClientKind.Web
        "android" -> AuditClientKind.Android
        "api" -> AuditClientKind.Api
        "system" -> AuditClientKind.System
        else -> AuditClientKind.Unknown
    }

private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

/** The chip text; the same rules as the web app (`clientLabel` in `audit-client.ts`). */
fun AuditClientDto?.clientLabel(string: (Int) -> String): String =
    when (clientKind()) {
        AuditClientKind.System -> string(R.string.admin_client_system)
        AuditClientKind.Unknown -> string(R.string.admin_client_unknown)
        AuditClientKind.Web -> this?.label.clean() ?: string(R.string.admin_client_web)
        AuditClientKind.Android -> this?.label.clean() ?: string(R.string.admin_client_android)
        AuditClientKind.Api -> this?.label.clean() ?: string(R.string.admin_client_api)
    }

fun AuditClientKind.icon(): ImageVector =
    when (this) {
        AuditClientKind.Web -> Icons.Outlined.Language
        AuditClientKind.Android -> Icons.Outlined.Android
        AuditClientKind.Api -> Icons.Outlined.Terminal
        AuditClientKind.System -> Icons.Outlined.Settings
        AuditClientKind.Unknown -> Icons.AutoMirrored.Outlined.HelpOutline
    }
