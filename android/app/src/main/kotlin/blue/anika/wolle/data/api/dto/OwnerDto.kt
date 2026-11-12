package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `owner` of a project or yarn (admin-scope contract): who owns the resource, so an administrator
 * who sees other people's items can badge them. [name] is the capitalised local part of the email;
 * [avatarUrl] is a server path loaded with the app's own credentials.
 */
@Serializable
data class OwnerDto(
    val id: Long,
    val email: String = "",
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * `access` of a project or yarn (sharing contract, `docs/sharing-api.md`): what the caller may do
 * with it. [role] is `owner`, `admin`, `editor` or `viewer`; [derived] marks a yarn read only
 * through a shared project. Enable controls from [canEdit]/[canManage], never from the role string;
 * the server stays the final authority.
 */
@Serializable
data class AccessDto(
    val role: String = ACCESS_OWNER,
    val derived: Boolean = false,
    @SerialName("can_edit") val canEdit: Boolean = true,
    @SerialName("can_manage") val canManage: Boolean = true,
    /**
     * The caller's direct sharing relationship, even when an administrator remains [ACCESS_ADMIN].
     */
    @SerialName("share_role") val shareRole: String? = null,
)

const val ACCESS_OWNER = "owner"
const val ACCESS_ADMIN = "admin"
const val ACCESS_EDITOR = "editor"
const val ACCESS_VIEWER = "viewer"
