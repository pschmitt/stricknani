package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/v2/activity`: the owner's recent project and yarn events, coalesced server side. */
@Serializable
data class ActivityActorDto(
    /**
     * `self`, `user`, `admin` (an administrator hidden from this viewer), `system` or `unknown`;
     * null on servers older than the field (see `activityActorRef`).
     */
    val kind: String? = null,
    /** Display name, never an email; only for `self` and `user`. */
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
    /** No account caused it: the purge job, a restore the server ran. */
    val system: Boolean = false,
    /**
     * The owner of the item for trash events an administrator did and for the automatic purge, or
     * the other account of a sharing event.
     */
    val subject: AuditSubjectDto? = null,
)

@Serializable
data class ActivityMetaDto(
    val limit: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_before") val nextBefore: Long? = null,
)

@Serializable data class ActivityPageDto(val data: List<ActivityItemDto>, val meta: ActivityMetaDto)
