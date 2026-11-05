package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ACCESS_ADMIN
import blue.anika.wolle.data.api.dto.ACCESS_EDITOR
import blue.anika.wolle.data.api.dto.ACCESS_OWNER
import blue.anika.wolle.data.api.dto.ACCESS_VIEWER
import blue.anika.wolle.data.api.dto.AccessDto
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity

/**
 * What the signed-in user may do with one cached project or yarn (sharing, `docs/sharing-api.md`),
 * from the synced `access` object. Rows without access info (the user's own, placeholders of an
 * offline create, rows from before sharing) are the owner's: full access. The UI enables controls
 * from the flags; the server stays the final authority and a refused write becomes a visible
 * conflict.
 */
data class ItemAccess(
    val role: String = ACCESS_OWNER,
    val derived: Boolean = false,
    val canEdit: Boolean = true,
    val canManage: Boolean = true,
) {
    /** A viewer or editor of somebody else's item, or a yarn visible through a shared project. */
    val isShared: Boolean
        get() = derived || role == ACCESS_VIEWER || role == ACCESS_EDITOR

    /** "Leave" removes the caller's own share; derived access has no share to leave. */
    val canLeave: Boolean
        get() = !derived && (role == ACCESS_VIEWER || role == ACCESS_EDITOR)

    companion object {
        val Own = ItemAccess()
    }
}

/** Builds the access of a cached row; a role without flags falls back to what the role implies. */
internal fun itemAccessOf(
    role: String?,
    derived: Boolean,
    canEdit: Boolean?,
    canManage: Boolean?,
): ItemAccess {
    if (role == null) return ItemAccess.Own
    val manages = role == ACCESS_OWNER || role == ACCESS_ADMIN
    return ItemAccess(
        role = role,
        derived = derived,
        canEdit = canEdit ?: (manages || role == ACCESS_EDITOR),
        canManage = canManage ?: manages,
    )
}

fun ProjectEntity.access(): ItemAccess = itemAccessOf(accessRole, accessDerived, canEdit, canManage)

fun YarnEntity.access(): ItemAccess = itemAccessOf(accessRole, accessDerived, canEdit, canManage)

/** True for rows that are visible to a non-administrator because somebody shared them. */
internal fun isSharedWithMe(role: String?, derived: Boolean): Boolean =
    derived || role == ACCESS_VIEWER || role == ACCESS_EDITOR

internal fun ItemAccess.toDto(): AccessDto = AccessDto(role, derived, canEdit, canManage)
