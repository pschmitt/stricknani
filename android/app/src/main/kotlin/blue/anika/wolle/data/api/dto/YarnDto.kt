package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `stricknani/routes/api/schemas.py`'s `YarnPhotoResponse`. */
@Serializable
data class YarnPhotoDto(
    val id: Int,
    val url: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String,
    @SerialName("alt_text") val altText: String,
    @SerialName("is_primary") val isPrimary: Boolean,
)

/**
 * `stricknani/routes/api/schemas.py`'s `YarnResponse`. All timestamp fields are the raw wire string
 * (may or may not carry a UTC offset depending on the backend's datetime - see `DateTimeUtils`) -
 * parsed only where actually needed, not at the DTO layer.
 */
@Serializable
data class YarnDto(
    val id: Int,
    val name: String,
    val description: String? = null,
    val brand: String? = null,
    val colorway: String? = null,
    @SerialName("dye_lot") val dyeLot: String? = null,
    @SerialName("fiber_content") val fiberContent: String? = null,
    @SerialName("weight_category") val weightCategory: String? = null,
    @SerialName("recommended_needles") val recommendedNeedles: String? = null,
    @SerialName("weight_grams") val weightGrams: Int? = null,
    @SerialName("length_meters") val lengthMeters: Int? = null,
    val notes: String? = null,
    val link: String? = null,
    @SerialName("link_archive") val linkArchive: String? = null,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("project_ids") val projectIds: List<Int> = emptyList(),
    val photos: List<YarnPhotoDto> = emptyList(),
)

/** `stricknani/routes/api/schemas.py`'s `YarnListItemResponse`. */
@Serializable
data class YarnListItemDto(
    val id: Int,
    val name: String,
    val brand: String? = null,
    val colorway: String? = null,
    @SerialName("weight_category") val weightCategory: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("preview_url") val previewUrl: String? = null,
)

/** `stricknani/routes/api/schemas.py`'s `YarnWriteRequest`. */
@Serializable
data class YarnWriteRequest(
    val name: String,
    val description: String? = null,
    val brand: String? = null,
    val colorway: String? = null,
    @SerialName("dye_lot") val dyeLot: String? = null,
    @SerialName("fiber_content") val fiberContent: String? = null,
    @SerialName("weight_category") val weightCategory: String? = null,
    @SerialName("recommended_needles") val recommendedNeedles: String? = null,
    @SerialName("weight_grams") val weightGrams: Int? = null,
    @SerialName("length_meters") val lengthMeters: Int? = null,
    val notes: String? = null,
    val link: String? = null,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean = false,
)

/** `stricknani/routes/api/schemas.py`'s `YarnPage`. */
@Serializable
data class YarnPageDto(
    val items: List<YarnListItemDto>,
    val page: Int,
    @SerialName("per_page") val perPage: Int,
    @SerialName("has_more") val hasMore: Boolean,
)

/** `stricknani/routes/api/schemas.py`'s `YarnSyncResponse`. */
@Serializable
data class YarnSyncResponseDto(
    val updated: List<YarnDto>,
    @SerialName("deleted_ids") val deletedIds: List<Int>,
    @SerialName("server_time") val serverTime: String,
    @SerialName("full_resync_required") val fullResyncRequired: Boolean = false,
)
