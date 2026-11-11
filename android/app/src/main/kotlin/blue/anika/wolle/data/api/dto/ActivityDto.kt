package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/v2/activity`: the owner's recent project and yarn events, coalesced server side. */
@Serializable
data class ActivityActorDto(
    val name: String? = null,
    @SerialName("is_self") val isSelf: Boolean = false,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class ActivityItemDto(
    /** The newest event of the run. */
    val id: Long,
    @SerialName("first_event_id") val firstEventId: Long = id,
    @SerialName("created_at") val createdAt: String,
    @SerialName("first_created_at") val firstCreatedAt: String? = null,
    val action: String,
    /** `project` or `yarn`. */
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: Int,
    @SerialName("entity_name") val entityName: String? = null,
    val exists: Boolean = false,
    val href: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val actor: ActivityActorDto = ActivityActorDto(),
    @SerialName("changed_fields") val changedFields: List<String> = emptyList(),
    val count: Int = 1,
    /** `active`, `in_trash` or `gone`; null on servers without the trash. */
    val state: String? = null,
    /** Only while [state] is `in_trash`. */
    val trash: TrashStateDto? = null,
    /** True only for the automatic purge after 90 days (no actor). */
    val system: Boolean = false,
    /** The owner of the item for trash events an administrator did and for the automatic purge. */
    val subject: AuditSubjectDto? = null,
)

@Serializable
data class ActivityMetaDto(
    val limit: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_before") val nextBefore: Long? = null,
)

@Serializable data class ActivityPageDto(val data: List<ActivityItemDto>, val meta: ActivityMetaDto)
