package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import blue.anika.wolle.data.api.dto.ProjectImportStepDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/** `rust/src/api_v2/imports.rs`: JSON in, `{data:{draft:{...camelCase...}}}` out. */
interface ImportsV2Api {
    @POST("api/v2/imports/preview")
    suspend fun previewImport(@Body request: ImportPreviewRequest): V2Envelope<ImportPreviewDto>
}

@Serializable
data class ImportPreviewRequest(
    val type: String = "url",
    val url: String,
    @SerialName("use_ai") val useAi: Boolean = false,
    /** `project` (default) or `yarn`. */
    val target: String = "project",
)

/** `PreviewData`. Only what the app needs; the rest of the body is ignored. */
@Serializable
data class ImportPreviewDto(
    val draft: ImportDraftDto,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean = false,
    @SerialName("ai_fallback") val aiFallback: Boolean = false,
)

/** `ImportDocumentDraft` (camelCase). */
@Serializable
data class ImportDraftDto(
    val sourceUrl: String? = null,
    val project: ImportDraftProjectDto = ImportDraftProjectDto(),
    val yarn: ImportDraftYarnDto? = null,
    val pageKind: String? = null,
    /** Field path to source: `structured`, `site`, `text` or `ai`. */
    val provenance: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

/** `YarnDetails` (camelCase): a yarn product as found on a shop or yarn database page. */
@Serializable
data class ImportDraftYarnDto(
    val name: String? = null,
    val brand: String? = null,
    val colorway: String? = null,
    val description: String? = null,
    val fiberContent: String? = null,
    val weightCategory: String? = null,
    val weightGrams: Int? = null,
    val lengthMeters: Int? = null,
    val recommendedNeedles: String? = null,
    val gauge: String? = null,
    val care: String? = null,
    val price: String? = null,
    val link: String? = null,
    val imageUrls: List<String> = emptyList(),
)

/** `ProjectImportDraft` (camelCase). `yarns`, `extras` and gauge details are not used yet. */
@Serializable
data class ImportDraftProjectDto(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val needles: String? = null,
    val otherMaterials: String? = null,
    val stitchSample: String? = null,
    val steps: List<ImportDraftStepDto> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val link: String? = null,
    val designer: String? = null,
    val sizes: String? = null,
    val difficulty: String? = null,
)

/** `StepImportDraft`. */
@Serializable
data class ImportDraftStepDto(
    val stepNumber: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val images: List<String> = emptyList(),
)

/**
 * v2 draft -> the shape the app's import confirmation already consumes
 * ([ProjectImportResponseDto]), so one mapper (`toPreview`) serves both API generations.
 */
fun ImportPreviewDto.toV1(): ProjectImportResponseDto =
    ProjectImportResponseDto(
        name = draft.project.name,
        category = draft.project.category,
        needles = draft.project.needles,
        stitchSample = draft.project.stitchSample,
        description = draft.project.description,
        otherMaterials = draft.project.otherMaterials,
        link = draft.project.link ?: draft.sourceUrl,
        designer = draft.project.designer,
        sizes = draft.project.sizes,
        difficulty = draft.project.difficulty,
        aiFields = draft.provenance.filterValues { it == "ai" }.keys.toList(),
        warnings = draft.warnings,
        isAiEnhanced = isAiEnhanced,
        aiFallback = aiFallback,
        imageUrls = draft.project.imageUrls,
        steps =
            draft.project.steps.map {
                ProjectImportStepDto(
                    stepNumber = it.stepNumber?.takeIf { number -> number > 0 },
                    title = it.title,
                    description = it.description,
                    images = it.images,
                )
            },
    )
