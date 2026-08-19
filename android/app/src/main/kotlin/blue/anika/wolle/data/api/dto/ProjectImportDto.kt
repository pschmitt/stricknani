package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The URL-import route's step payload. Fields are nullable because importers vary in detail. */
@Serializable
data class ProjectImportStepDto(
    @SerialName("step_number") val stepNumber: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val images: List<String> = emptyList(),
)

/**
 * JSON returned by `POST /projects/import` for a URL import.
 *
 * Unknown fields are intentionally ignored by the app's shared JSON configuration: the web importer
 * grows independently (AI metadata, trace ids, and image hints) and the offline project cache only
 * needs the fields represented here.
 */
@Serializable
data class ProjectImportResponseDto(
    val name: String? = null,
    val title: String? = null,
    val category: String? = null,
    val needles: String? = null,
    @SerialName("stitch_sample") val stitchSample: String? = null,
    val description: String? = null,
    val notes: String? = null,
    @SerialName("other_materials") val otherMaterials: String? = null,
    val tags: List<String> = emptyList(),
    val link: String? = null,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean = false,
    @SerialName("ai_fallback") val aiFallback: Boolean = false,
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    val steps: List<ProjectImportStepDto> = emptyList(),
)
