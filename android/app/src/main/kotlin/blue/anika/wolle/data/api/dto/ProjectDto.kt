package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `stricknani/routes/api/schemas.py`'s `StepResponse`. */
@Serializable
data class StepDto(
    val id: Int,
    val title: String,
    val description: String? = null,
    @SerialName("step_number") val stepNumber: Int,
)

/** `stricknani/routes/api/schemas.py`'s `StepWriteRequest`. */
@Serializable
data class StepWriteRequest(
    /** Existing ids are preserved during reorder/update so step images survive. */
    val id: Int? = null,
    val title: String,
    val description: String? = null,
    @SerialName("step_number") val stepNumber: Int = 0,
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
)

/** `stricknani/routes/api/schemas.py`'s `ImageResponse`. */
@Serializable
data class ImageDto(
    val id: Int,
    val url: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String,
    @SerialName("alt_text") val altText: String,
    @SerialName("image_type") val imageType: String,
    @SerialName("is_title_image") val isTitleImage: Boolean,
    @SerialName("is_stitch_sample") val isStitchSample: Boolean,
    @SerialName("step_id") val stepId: Int? = null,
)

/** `stricknani/routes/api/schemas.py`'s `AttachmentResponse`. */
@Serializable
data class AttachmentDto(
    val id: Int,
    val filename: String,
    @SerialName("original_filename") val originalFilename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val url: String,
)

/**
 * `stricknani/routes/api/schemas.py`'s `ProjectResponse`. Timestamp fields are the raw wire
 * string - see `YarnDto`'s kdoc for why.
 */
@Serializable
data class ProjectDto(
    val id: Int,
    val name: String,
    val category: String? = null,
    val yarn: String? = null,
    val needles: String? = null,
    @SerialName("stitch_sample") val stitchSample: String? = null,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("other_materials") val otherMaterials: String? = null,
    val tags: List<String> = emptyList(),
    val link: String? = null,
    @SerialName("link_archive") val linkArchive: String? = null,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("yarn_ids") val yarnIds: List<Int> = emptyList(),
    val steps: List<StepDto> = emptyList(),
    val images: List<ImageDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
)

/** `stricknani/routes/api/schemas.py`'s `ProjectListItemResponse`. */
@Serializable
data class ProjectListItemDto(
    val id: Int,
    val name: String,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("preview_url") val previewUrl: String? = null,
)

/** `stricknani/routes/api/schemas.py`'s `ProjectWriteRequest`. */
@Serializable
data class ProjectWriteRequest(
    val name: String,
    val category: String? = null,
    val needles: String? = null,
    @SerialName("stitch_sample") val stitchSample: String? = null,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("other_materials") val otherMaterials: String? = null,
    val tags: List<String> = emptyList(),
    val link: String? = null,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean = false,
    @SerialName("yarn_ids") val yarnIds: List<Int> = emptyList(),
    val steps: List<StepWriteRequest> = emptyList(),
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    /**
     * SNA-33: the `updatedAt` this edit was based on. When set, the server rejects the write with
     * 409 (current server state in the body) if the project has since changed - see
     * `ProjectRepository.updateProject`/`WriteReplayWorker` for how this drives conflict handling.
     */
    @SerialName("expected_updated_at") val expectedUpdatedAt: String? = null,
)

/** `stricknani/routes/api/schemas.py`'s `ProjectPage`. */
@Serializable
data class ProjectPageDto(
    val items: List<ProjectListItemDto>,
    val page: Int,
    @SerialName("per_page") val perPage: Int,
    @SerialName("has_more") val hasMore: Boolean,
)

/** `stricknani/routes/api/schemas.py`'s `ProjectSyncResponse`. */
@Serializable
data class ProjectSyncResponseDto(
    val updated: List<ProjectDto>,
    @SerialName("deleted_ids") val deletedIds: List<Int>,
    @SerialName("server_time") val serverTime: String,
    @SerialName("full_resync_required") val fullResyncRequired: Boolean = false,
)
