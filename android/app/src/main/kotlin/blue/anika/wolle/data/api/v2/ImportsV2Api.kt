package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.ImportAssetDto
import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import blue.anika.wolle.data.api.dto.ProjectImportStepDto
import blue.anika.wolle.data.api.dto.ProjectImportYarnDto
import blue.anika.wolle.data.api.dto.ProjectImportYarnMatchDto
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/** `src/api_v2/imports.rs`: import drafts (the server runs the imports). */
interface ImportsV2Api {
    /** Starts an import on the server (202); it keeps running when the app is closed. */
    @POST("api/v2/imports/drafts")
    suspend fun createDraft(@Body request: ImportDraftRequest): V2Envelope<ImportJobDto>

    /** The user's drafts, newest first, without their `preview`. */
    @GET("api/v2/imports/drafts") suspend fun listDrafts(): V2Envelope<List<ImportJobDto>>

    /** One draft; carries the `preview` once it is ready. */
    @GET("api/v2/imports/drafts/{id}")
    suspend fun getDraft(@Path("id") id: String): V2Envelope<ImportJobDto>

    /**
     * Reads one document (PDF, image, HTML, text, Markdown) at once, without a draft: multipart
     * `file` and `use_ai`, answered with the preview. Same contract as the web's file import.
     */
    @Multipart
    @POST("api/v2/imports/preview-file")
    suspend fun previewFile(
        @Part file: MultipartBody.Part,
        @Part useAi: MultipartBody.Part,
    ): V2Envelope<ImportPreviewDto>

    @DELETE("api/v2/imports/drafts/{id}") suspend fun deleteDraft(@Path("id") id: String)
}

/**
 * `POST /api/v2/imports/drafts`. The app's [kotlinx.serialization.json.Json] leaves out default
 * values, but the server needs `type` (it selects the request variant) and the explicit `target`:
 * both are always encoded. A link import sets [url], a text import [text] (type `text`, no target
 * needed). [locale] is the language of the notes the import writes.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImportDraftRequest(
    @EncodeDefault val type: String = "url",
    val url: String? = null,
    val text: String? = null,
    @SerialName("use_ai") val useAi: Boolean = false,
    /** `project` or `yarn`. */
    @EncodeDefault val target: String = "project",
    val locale: String? = null,
)

/** Largest link-less source (text or file) the server reads: 5 MiB, as on the web. */
const val MAX_IMPORT_SOURCE_BYTES = 5 * 1024 * 1024

/** What the document import accepts: the web's file picker types. */
val IMPORT_FILE_MIME_TYPES =
    arrayOf("application/pdf", "image/*", "text/plain", "text/html", "text/markdown")

/** The server's import draft: what it is, how far it is and, once ready, what it found. */
@Serializable
data class ImportJobDto(
    val id: String,
    val target: String = "project",
    @SerialName("source_kind") val sourceKind: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("use_ai") val useAi: Boolean = false,
    /** `running`, `ready` or `failed`. */
    val status: String = "running",
    val title: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("error_status") val errorStatus: Int? = null,
    val error: ImportJobErrorDto? = null,
    val preview: ImportPreviewDto? = null,
) {
    val isRunning: Boolean
        get() = status == STATUS_RUNNING

    val isReady: Boolean
        get() = status == STATUS_READY

    val isFailed: Boolean
        get() = status == STATUS_FAILED

    val isYarn: Boolean
        get() = target == "yarn"

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_READY = "ready"
        const val STATUS_FAILED = "failed"
    }
}

/** `{error:{code,message}}` of a failed draft. */
@Serializable
data class ImportJobErrorDto(val error: ImportJobErrorBody? = null) {
    val code: String?
        get() = error?.code
}

@Serializable data class ImportJobErrorBody(val code: String? = null, val message: String? = null)

/** `PreviewData`. Only what the app needs; the rest of the body is ignored. */
@Serializable
data class ImportPreviewDto(
    val draft: ImportDraftDto,
    @SerialName("is_ai_enhanced") val isAiEnhanced: Boolean = false,
    @SerialName("ai_fallback") val aiFallback: Boolean = false,
    /** Suggested stash yarn per imported yarn (index into `draft.project.yarns`). */
    @SerialName("yarn_matches") val yarnMatches: List<ProjectImportYarnMatchDto> = emptyList(),
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
    val assets: List<ImportAssetDto> = emptyList(),
    val link: String? = null,
    val designer: String? = null,
    val sizes: String? = null,
    val difficulty: String? = null,
    val yarns: List<ProjectImportYarnDto> = emptyList(),
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
        yarns = draft.project.yarns,
        yarnMatches = yarnMatches,
        imageUrls = draft.project.imageUrls,
        assets = draft.project.assets,
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
