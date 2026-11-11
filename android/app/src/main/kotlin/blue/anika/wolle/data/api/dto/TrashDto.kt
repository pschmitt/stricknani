package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire types of `GET /api/v2/trash` (src/api_v2/trash.rs): everything the owner (or, with
 * `scope=all`, an administrator) deleted in the last 90 days and can still restore. Every field
 * that is not essential is nullable or defaulted, and unknown keys are ignored, so a newer server
 * (more kinds, more fields) never fails the whole list.
 */

/** Where a child item (step, image, attachment, photo) lived: its project or yarn. */
@Serializable
data class TrashParentDto(
    /** `project` or `yarn`. */
    val kind: String = "",
    val id: Long = 0,
    val name: String? = null,
)

@Serializable
data class TrashItemDto(
    /**
     * `project`, `yarn`, `category`, `step`, `image` (project title or step image), `attachment`
     * (project attachment) or `photo` (yarn photo). Kept as a string: an unknown kind still lists.
     */
    val kind: String,
    val id: Long,
    val name: String? = null,
    val owner: OwnerDto? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("purge_at") val purgeAt: String? = null,
    /** Whole days left, rounded up; 0 when the item is due for permanent deletion. */
    @SerialName("days_remaining") val daysRemaining: Int = 0,
    /** Only for child kinds. */
    val parent: TrashParentDto? = null,
    /** Only for child kinds: a short extra line (a step number, a file name). */
    val detail: String? = null,
    /**
     * A project's cover, a yarn's photo or the picture itself for an image or photo; an owner- and
     * administrator-scoped `/media/...` path that also works while the item is in the trash.
     */
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable
data class TrashMetaDto(
    val scope: String = TRASH_SCOPE_MINE,
    @SerialName("retention_days") val retentionDays: Int = TRASH_RETENTION_DAYS,
)

@Serializable
data class TrashListDto(
    val data: List<TrashItemDto> = emptyList(),
    val meta: TrashMetaDto = TrashMetaDto(),
)

const val TRASH_SCOPE_MINE = "mine"
const val TRASH_SCOPE_ALL = "all"

/** The server's default retention; the real value comes from `meta.retention_days`. */
const val TRASH_RETENTION_DAYS = 90

/**
 * `trash` of an audit event or activity item: the item sits in the trash. Only sent while the
 * resource is in the trash.
 */
@Serializable
data class TrashStateDto(
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("purge_at") val purgeAt: String? = null,
    @SerialName("days_remaining") val daysRemaining: Int = 0,
    @SerialName("can_restore") val canRestore: Boolean = false,
)

/** `resource.state` of an audit event and `state` of an activity item. */
const val RESOURCE_STATE_ACTIVE = "active"
const val RESOURCE_STATE_IN_TRASH = "in_trash"
const val RESOURCE_STATE_GONE = "gone"
