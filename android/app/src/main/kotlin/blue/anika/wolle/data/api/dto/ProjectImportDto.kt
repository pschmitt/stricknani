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
 * A diagram image (`kind` "diagram") or PDF ("pdf") found on the imported page. The server stores
 * selected ones as project attachments when the project is created.
 */
@Serializable
data class ImportAssetDto(
    val url: String,
    val kind: String = "diagram",
    val label: String? = null,
) {
    val isPdf: Boolean
        get() = kind == "pdf"
}

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
    val designer: String? = null,
    val sizes: String? = null,
    val difficulty: String? = null,
    /** Field paths the model supplied (`provenance` entries with source `ai`). */
    @SerialName("ai_fields") val aiFields: List<String> = emptyList(),
    /** Short machine codes such as `looks_like_yarn_page`. */
    val warnings: List<String> = emptyList(),
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean = false,
    @SerialName("ai_fallback") val aiFallback: Boolean = false,
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    val steps: List<ProjectImportStepDto> = emptyList(),
    /** Yarns the pattern needs, cleaned by the server (v2 only). */
    val yarns: List<ProjectImportYarnDto> = emptyList(),
    /** Suggested stash yarn per entry of [yarns] (v2 only). */
    @SerialName("yarn_matches") val yarnMatches: List<ProjectImportYarnMatchDto> = emptyList(),
    val assets: List<ImportAssetDto> = emptyList(),
)

/** One yarn of an imported pattern. [weight] and [length] are text such as "50 g" and "105 m". */
@Serializable
data class ProjectImportYarnDto(
    val name: String? = null,
    val brand: String? = null,
    val colorway: String? = null,
    val weight: String? = null,
    val length: String? = null,
    val weightCategory: String? = null,
    val fiberContent: String? = null,
    val link: String? = null,
    val imageUrl: String? = null,
    val amount: String? = null,
    val notes: String? = null,
)

/** The stash yarn the server suggests for the imported yarn at [index]. */
@Serializable
data class ProjectImportYarnMatchDto(
    val index: Int,
    @SerialName("yarn_id") val yarnId: Int,
    val score: Double = 0.0,
    val exact: Boolean = false,
)
