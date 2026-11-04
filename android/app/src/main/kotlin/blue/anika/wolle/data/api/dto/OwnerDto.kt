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
