package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A `Person` of the sharing contract: never an email, admin flag or activity. */
@Serializable
data class PersonDto(
    val id: Long,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** One entry of `GET /{kind}/{id}/shares`. */
@Serializable
data class ShareDto(
    val user: PersonDto,
    val role: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** `{owner, shares}` of `GET /{kind}/{id}/shares`. */
@Serializable
data class ShareListDto(val owner: PersonDto, val shares: List<ShareDto> = emptyList())

/** Body of `POST /{kind}/{id}/shares`: exactly these two fields. */
@Serializable
data class ShareCreateRequest(@SerialName("user_id") val userId: Long, val role: String)

/** Body of `PATCH /{kind}/{id}/shares/{user_id}`. */
@Serializable data class ShareRoleRequest(val role: String)

/** Body of `POST /{kind}/{id}/transfer`; `move_linked_yarns` is for projects only. */
@Serializable
data class TransferRequest(
    @SerialName("new_owner_id") val newOwnerId: Long,
    @SerialName("move_linked_yarns") val moveLinkedYarns: Boolean? = null,
)

/** Result of a transfer: [access] is the caller's access afterwards (an editor). */
@Serializable
data class TransferResultDto(
    @SerialName("new_owner") val newOwner: PersonDto,
    val access: AccessDto,
    @SerialName("moved_yarn_ids") val movedYarnIds: List<Int> = emptyList(),
)

/** `{items, page, per_page, has_more}` of `GET /people`. */
@Serializable
data class PeoplePageDto(
    val items: List<PersonDto> = emptyList(),
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 20,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** One yarn of `GET /projects/{id}/yarn-candidates`: the owner's yarns, no photos. */
@Serializable
data class YarnCandidateDto(
    val id: Int,
    val name: String,
    val brand: String? = null,
    val colorway: String? = null,
    @SerialName("weight_category") val weightCategory: String? = null,
    val linked: Boolean = false,
)

@Serializable
data class YarnCandidatesPageDto(
    val items: List<YarnCandidateDto> = emptyList(),
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 50,
    @SerialName("has_more") val hasMore: Boolean = false,
)
