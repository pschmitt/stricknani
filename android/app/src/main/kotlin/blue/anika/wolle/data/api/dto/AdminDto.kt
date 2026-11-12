package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Wire types for the administrator API (`src/api_v2/{admin,audit}.rs`, structs in
 * `src/{admin_service,audit_service}.rs`). The web client parses the same shapes in
 * `web/src/lib/features/admin/admin-api.ts`.
 */

/** `{ "data": [...], "meta": {...} }` page metadata. Pages hold 50 rows. */
@Serializable
data class AdminPageMetaDto(
    val page: Int,
    @SerialName("per_page") val perPage: Int,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class AdminUserDto(
    val id: Long,
    val email: String,
    val active: Boolean,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** Linked single sign-on identities; null on servers that do not say. */
    @SerialName("linked_identities") val linkedIdentities: Int? = null,
)

@Serializable data class AdminUsersPageDto(val data: List<AdminUserDto>, val meta: AdminPageMetaDto)

@Serializable data class AdminUserEnvelopeDto(val data: AdminUserDto)

@Serializable
data class CreateAdminUserRequest(
    val email: String,
    val password: String,
    @SerialName("is_admin") val isAdmin: Boolean,
)

@Serializable data class SetUserActiveRequest(val active: Boolean)

@Serializable data class ResetPasswordRequest(@SerialName("new_password") val newPassword: String)

@Serializable
data class ResetPasswordDataDto(
    val id: Long,
    @SerialName("session_revoked") val sessionRevoked: Boolean = true,
)

@Serializable data class ResetPasswordEnvelopeDto(val data: ResetPasswordDataDto)

/** `ImpersonatedUser` in `impersonation.rs`. */
@Serializable data class ImpersonatedUserDto(val id: Long, val email: String, val name: String)

/** `ImpersonatingAdmin` in `impersonation.rs`. */
@Serializable data class ImpersonatingAdminDto(val id: Long, val email: String)

/**
 * `POST /api/v2/admin/users/{id}/impersonation-token`: the short-lived `sni_` credential (shown
 * once) plus who acts as whom. [expiresAt] is epoch seconds.
 */
@Serializable
data class ImpersonationStartDto(
    val token: String,
    val user: ImpersonatedUserDto,
    val admin: ImpersonatingAdminDto,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable data class ImpersonationStartEnvelopeDto(val data: ImpersonationStartDto)

@Serializable data class ImpersonationStopDto(val stopped: Boolean = false)

@Serializable data class ImpersonationStopEnvelopeDto(val data: ImpersonationStopDto)

/** `AuditResource` in `audit_service.rs`: what the event was about, resolved by the server. */
@Serializable
data class AuditResourceDto(
    /** `project`, `yarn`, `category`, or null when the type is not one the server can link. */
    val kind: String? = null,
    val id: Long = 0,
    val name: String? = null,
    @SerialName("name_source") val nameSource: String = "unavailable",
    val href: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val exists: Boolean = false,
    /**
     * `active`, `in_trash` or `gone` (see [RESOURCE_STATE_ACTIVE] and friends). Null on servers
     * without the trash: read it with `itemState()`, which falls back to [exists].
     */
    val state: String? = null,
    /** Only while [state] is `in_trash`: when it goes and whether the viewer can restore it. */
    val trash: TrashStateDto? = null,
)

/**
 * `AuditMedia` in `audit_service.rs`: the picture or file an upload/removal event was about. [url]
 * and [thumbnailUrl] are null once the file has been deleted ([exists] is false).
 */
@Serializable
data class AuditMediaDto(
    /** `image`, `photo` or `attachment`. */
    val kind: String = "image",
    val id: Long = 0,
    val name: String? = null,
    val exists: Boolean = false,
    @SerialName("is_image") val isImage: Boolean = false,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val url: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

/** One picture of a history event's `media` label map: where to load it and its thumbnail. */
@Serializable
data class HistoryMediaDto(
    val url: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable
data class AuditEventDto(
    val id: Long,
    @SerialName("actor_email") val actorEmail: String? = null,
    @SerialName("actor_avatar_url") val actorAvatarUrl: String? = null,
    @SerialName("legacy_actor_user_id") val legacyActorUserId: Long? = null,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: Long,
    val action: String,
    @SerialName("created_at") val createdAt: String,
    val resource: AuditResourceDto = AuditResourceDto(),
    @SerialName("has_changes") val hasChanges: Boolean = false,
    val media: AuditMediaDto? = null,
    /** The other account of a sharing event; absent on every other event (never an email). */
    val subject: AuditSubjectDto? = null,
    /**
     * Pictures named by this event's media changes (picture order, cover or primary photo), by the
     * file label that appears in the change values. Labels of deleted photos are absent, and the
     * key is missing on old servers and events without such changes.
     */
    @SerialName("media_thumbnails") val mediaThumbs: Map<String, HistoryMediaDto> = emptyMap(),
    /** True only for the automatic purge after 90 days: nobody did it, so there is no actor. */
    val system: Boolean = false,
)

/**
 * `subject` of `shared`, `share_role_changed`, `share_revoked`, `share_left` and
 * `ownership_transferred` events: [id] and [name] are null once that account was deleted, [role] is
 * the share role and absent for transfers. Trash events an administrator did, and the automatic
 * purge, carry the owner of the item here (with no role).
 */
@Serializable
data class AuditSubjectDto(val id: Long? = null, val name: String? = null, val role: String? = null)

@Serializable data class AuditPageDto(val data: List<AuditEventDto>, val meta: AdminPageMetaDto)

/**
 * `ChangeSetV1`. Values are kept as raw JSON and decoded with [parseChangeValue] so that a value
 * type this app version does not know yet shows up as [ChangeValue.Unknown] instead of failing the
 * whole event.
 */
@Serializable
data class ChangeItemDto(val field: String, val before: JsonElement, val after: JsonElement)

@Serializable
data class ChangeSetDto(val version: Int = 1, val items: List<ChangeItemDto> = emptyList())

@Serializable
data class AuditDetailDto(
    val event: AuditEventDto,
    val changes: ChangeSetDto? = null,
    /** `not_recorded`, `malformed` or `too_large` when [changes] is null. */
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
)

@Serializable data class AuditDetailEnvelopeDto(val data: AuditDetailDto)

/** One step inside a `steps` change value. */
data class ChangeStep(val number: Int, val title: String, val description: String?)

/** A decoded `ChangeValue` (`type` + `value`, see `admin-api.ts`). */
sealed interface ChangeValue {
    data class Text(val value: String?) : ChangeValue

    data class TextList(val values: List<String>) : ChangeValue

    data class Integer(val value: Int?) : ChangeValue

    data class Flag(val value: Boolean) : ChangeValue

    data class Steps(val steps: List<ChangeStep>) : ChangeValue

    data class Yarns(val names: List<String>) : ChangeValue

    /** Links are never shown, only whether one is set. */
    data class LinkChanged(val present: Boolean) : ChangeValue

    data class LegacyText(val value: String) : ChangeValue

    data object Unknown : ChangeValue
}

fun parseChangeValue(element: JsonElement): ChangeValue {
    val obj = element as? JsonObject ?: return ChangeValue.Unknown
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return ChangeValue.Unknown
    val value = obj["value"]
    return runCatching {
            when (type) {
                "text" -> ChangeValue.Text(value?.jsonPrimitive?.contentOrNull)
                "text_list" -> ChangeValue.TextList(value.stringList())
                "yarns" -> ChangeValue.Yarns(value.stringList())
                "integer" -> ChangeValue.Integer(value?.jsonPrimitive?.intOrNull)
                "boolean" -> ChangeValue.Flag(value!!.jsonPrimitive.boolean)
                "link_changed" -> ChangeValue.LinkChanged(value!!.jsonPrimitive.boolean)
                "legacy_text" -> ChangeValue.LegacyText(value!!.jsonPrimitive.content)
                "steps" ->
                    ChangeValue.Steps(
                        value!!.jsonArray.map { step ->
                            val s = step.jsonObject
                            ChangeStep(
                                number = s["number"]!!.jsonPrimitive.int,
                                title = s["title"]!!.jsonPrimitive.content,
                                description = s["description"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    )
                else -> ChangeValue.Unknown
            }
        }
        .getOrDefault(ChangeValue.Unknown)
}

private fun JsonElement?.stringList(): List<String> =
    this?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

/** The API's `{ "error": { "code", "message", "fields" } }` error body. */
@Serializable data class ApiErrorEnvelopeDto(val error: ApiErrorDto? = null)

@Serializable
data class ApiErrorDto(
    val code: String? = null,
    val message: String? = null,
    val fields: Map<String, String>? = null,
)
