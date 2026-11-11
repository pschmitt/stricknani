package blue.anika.wolle.ui.trash

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.TrashItemDto
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.data.repository.TrashKind
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.util.DateTimeUtils

/*
 * Pure rules behind the Trash screen and the trash wording of the audit log, mirroring the web's
 * trash page: what an item is called, how many days it has left and what a failed request says.
 */

/** The label of a kind ("Project", "Step", ...); an unknown kind of a newer server reads "Item". */
@StringRes
fun trashKindLabelRes(kind: String): Int =
    when (TrashKind.fromWire(kind)) {
        TrashKind.Project -> R.string.trash_kind_project
        TrashKind.Yarn -> R.string.trash_kind_yarn
        TrashKind.Category -> R.string.trash_kind_category
        TrashKind.Step -> R.string.trash_kind_step
        TrashKind.Image -> R.string.trash_kind_image
        TrashKind.Attachment -> R.string.trash_kind_attachment
        TrashKind.Photo -> R.string.trash_kind_photo
        null -> R.string.trash_kind_item
    }

/** The "In project “X”" line of a child item; null for an item without a known parent. */
@StringRes
fun trashParentRes(parentKind: String): Int =
    if (parentKind == "yarn") R.string.trash_parent_yarn else R.string.trash_parent_project

/** The sentence "N days left" (a plural) or "Due for deletion" when [days] is 0. */
sealed interface DaysLeft {
    data class Days(val days: Int) : DaysLeft

    /** 0 days: the next purge removes it. */
    data object Due : DaysLeft
}

fun daysLeft(daysRemaining: Int): DaysLeft =
    if (daysRemaining <= 0) DaysLeft.Due else DaysLeft.Days(daysRemaining)

@PluralsRes val DAYS_LEFT_PLURAL: Int = R.plurals.trash_days_left

/** One row of the list, ready to draw. */
data class TrashRowModel(
    val ref: TrashRef,
    @StringRes val kindLabelRes: Int,
    val name: String?,
    val parentKind: String?,
    val parentName: String?,
    val detail: String?,
    val ownerName: String?,
    val ownerEmail: String?,
    val ownerAvatarUrl: String?,
    /** True when the owner is somebody else than the signed-in user: the row says "Owner: X". */
    val ownerIsForeign: Boolean,
    /** False: only the owner or an administrator can restore or delete it; no buttons. */
    val canRestore: Boolean,
    val deletedAt: String?,
    val daysLeft: DaysLeft,
)

/**
 * The row of an item. The owner is named for an administrator's "All users" list ([showOwner]) and
 * whenever it is somebody else than [currentUserId] (an item the user trashed in a shared project
 * or yarn); the signed-in user knows their own items.
 */
fun trashRow(item: TrashItemDto, showOwner: Boolean, currentUserId: Long? = null): TrashRowModel {
    val owner = item.owner
    val foreign = owner != null && currentUserId != null && owner.id != currentUserId
    val named = owner?.takeIf { showOwner || foreign }
    return TrashRowModel(
        ref = TrashRef(item.kind, item.id),
        kindLabelRes = trashKindLabelRes(item.kind),
        name = item.name?.trim()?.ifBlank { null },
        parentKind = item.parent?.kind?.ifBlank { null },
        parentName = item.parent?.name?.trim()?.ifBlank { null },
        detail = item.detail?.trim()?.ifBlank { null },
        ownerName = named?.let { it.name.trim().ifBlank { it.email.ifBlank { null } } },
        ownerEmail = named?.email?.ifBlank { null },
        ownerAvatarUrl = named?.avatarUrl,
        ownerIsForeign = foreign,
        canRestore = item.canRestore,
        deletedAt = item.deletedAt,
        daysLeft = daysLeft(item.daysRemaining),
    )
}

/** When an item was deleted, in epoch millis; null when the server's timestamp is unreadable. */
fun trashDeletedMillis(deletedAt: String?): Long? = deletedAt?.let {
    runCatching { DateTimeUtils.parseToEpochMillis(it) }.getOrNull()
}

/** Newest deletion first; ties keep the server order (the website's `sortTrash`). */
fun sortTrash(items: List<TrashItemDto>): List<TrashItemDto> = items.sortedByDescending {
    trashDeletedMillis(it.deletedAt) ?: 0L
}

/** The scope of the list: an administrator can switch to everyone's trash. */
enum class TrashScope(val wire: String) {
    Mine("mine"),
    All("all"),
}

/** The message of a failed restore (or Undo); a child whose parent is trashed says so. */
@StringRes
fun restoreFailureRes(failure: TrashFailure): Int =
    when (failure) {
        TrashFailure.ParentInTrash -> R.string.error_trash_restore_parent
        TrashFailure.NameTaken -> R.string.error_trash_name_taken
        TrashFailure.NotFound -> R.string.error_trash_gone
        TrashFailure.Offline -> R.string.error_trash_offline
        TrashFailure.NotAllowed,
        TrashFailure.Unsupported,
        TrashFailure.Other -> R.string.error_trash_restore_failed
    }

/** The message of a failed delete-forever. */
@StringRes
fun purgeFailureRes(failure: TrashFailure): Int =
    when (failure) {
        TrashFailure.NotFound -> R.string.error_trash_gone
        TrashFailure.Offline -> R.string.error_trash_offline
        TrashFailure.NotAllowed,
        TrashFailure.ParentInTrash,
        TrashFailure.NameTaken,
        TrashFailure.Unsupported,
        TrashFailure.Other -> R.string.error_trash_purge_failed
    }

/**
 * Whole days until [purgeAt] (rounded up, never negative), or null when the timestamp is missing or
 * unreadable. The banner of a trashed project or yarn reads "It will be deleted permanently in N
 * days." and, at 0, "It is due for deletion.".
 */
fun daysUntilPurge(purgeAt: String?, nowMillis: Long): Int? {
    val millis = trashDeletedMillis(purgeAt) ?: return null
    val left = millis - nowMillis
    if (left <= 0) return 0
    val day = 24L * 60 * 60 * 1000
    return ((left + day - 1) / day).toInt()
}
