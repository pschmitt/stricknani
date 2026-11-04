package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.AttachmentDto
import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.OwnerDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire shapes of `src/api_v2/{projects,yarns}.rs`. Identical to v1 except for the added
 * server-rendered `*_html` companions. The shapes that did not change are reused from the v1 DTOs
 * (`ImageDto`, `AttachmentDto`, `YarnPhotoDto`, `CategoryDto`, `ProjectListItemDto`,
 * `YarnListItemDto`, `ProjectWriteRequest`, `YarnWriteRequest`, `CategoryCreateRequest`,
 * `CategoryUpdateRequest`): both versions serialize the same Rust structs (`crate::media::*Response`,
 * `ProjectListItem`, `YarnListItemResponse`, ...). See `docs/v2-migration.md`.
 */

/** `StepDto` in `api_v2/projects.rs`: v1's `StepResponse` plus `description_html`. */
@Serializable
data class StepV2Dto(
    val id: Int,
    val title: String,
    val description: String? = null,
    @SerialName("description_html") val descriptionHtml: String? = null,
    @SerialName("step_number") val stepNumber: Int,
)

/** `ProjectDto` in `api_v2/projects.rs`. */
@Serializable
data class ProjectV2Dto(
    val id: Int,
    val name: String,
    val category: String? = null,
    val yarn: String? = null,
    val needles: String? = null,
    @SerialName("stitch_sample") val stitchSample: String? = null,
    val description: String? = null,
    @SerialName("description_html") val descriptionHtml: String? = null,
    val notes: String? = null,
    @SerialName("notes_html") val notesHtml: String? = null,
    @SerialName("other_materials") val otherMaterials: String? = null,
    @SerialName("other_materials_html") val otherMaterialsHtml: String? = null,
    val tags: List<String> = emptyList(),
    val link: String? = null,
    @SerialName("link_archive") val linkArchive: String? = null,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("yarn_ids") val yarnIds: List<Int> = emptyList(),
    val steps: List<StepV2Dto> = emptyList(),
    val images: List<ImageDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val owner: OwnerDto? = null,
)

/** `YarnDto` in `api_v2/yarns.rs`. */
@Serializable
data class YarnV2Dto(
    val id: Int,
    val name: String,
    val description: String? = null,
    @SerialName("description_html") val descriptionHtml: String? = null,
    val brand: String? = null,
    val colorway: String? = null,
    @SerialName("dye_lot") val dyeLot: String? = null,
    @SerialName("fiber_content") val fiberContent: String? = null,
    @SerialName("weight_category") val weightCategory: String? = null,
    @SerialName("recommended_needles") val recommendedNeedles: String? = null,
    @SerialName("weight_grams") val weightGrams: Int? = null,
    @SerialName("length_meters") val lengthMeters: Int? = null,
    val notes: String? = null,
    @SerialName("notes_html") val notesHtml: String? = null,
    val link: String? = null,
    @SerialName("link_archive") val linkArchive: String? = null,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("project_ids") val projectIds: List<Int> = emptyList(),
    val photos: List<YarnPhotoDto> = emptyList(),
    val owner: OwnerDto? = null,
)

/** Body of the three media-order routes: the complete desired order, no version or If-Match. */
@Serializable data class MediaOrderRequest(val ids: List<Int>)

/** `{data:{ids, updated_at}}` of a successful reorder; `updated_at` is the parent's new stamp. */
@Serializable
data class MediaOrderDto(val ids: List<Int>, @SerialName("updated_at") val updatedAt: String)
