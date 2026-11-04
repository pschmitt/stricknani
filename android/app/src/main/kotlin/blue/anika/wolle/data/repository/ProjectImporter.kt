package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ProjectImportApi
import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import blue.anika.wolle.data.api.v2.ImportPreviewRequest
import blue.anika.wolle.data.api.v2.ImportsV2Api
import blue.anika.wolle.data.api.v2.toV1
import javax.inject.Inject
import javax.inject.Singleton

/** A fact from the pattern page that has no field of its own and is kept in the project notes. */
enum class ImportDetailKind {
    Designer,
    Sizes,
    Difficulty,
}

data class ImportDetail(val kind: ImportDetailKind, val value: String)

/** Data that can be confirmed and then written through the normal offline project outbox. */
data class ImportedProjectPreview(
    val sourceUrl: String,
    val request: ProjectWriteRequest,
    val imageUrls: List<String>,
    val aiFallback: Boolean,
    /** Designer, sizes and difficulty; the caller turns them into localized note lines. */
    val details: List<ImportDetail> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Field paths (`project.needles`) the AI model supplied; shown as a hint, not saved. */
    val aiFields: List<String> = emptyList(),
) {
    /** The same preview with only [selected] pictures attached when it is saved. */
    fun withImages(selected: List<String>): ImportedProjectPreview =
        copy(request = request.copy(imageUrls = selected))
}

/** Boundary for importing project data, kept small so the UI state machine is unit-testable. */
interface ProjectImporter {
    suspend fun importFromUrl(url: String): ImportedProjectPreview
}

/**
 * Imports through `POST /api/v2/imports/preview` (SNA-87), or the web `POST /projects/import` route
 * on a server without v2. AI is requested whenever the server reports a provider.
 */
@Singleton
class ApiProjectImporter
@Inject
constructor(
    private val api: ProjectImportApi,
    private val v2Api: ImportsV2Api,
    private val capabilities: ApiCapabilities,
) : ProjectImporter {
    override suspend fun importFromUrl(url: String): ImportedProjectPreview =
        if (capabilities.useV2()) {
            val useAi = capabilities.current.importsAi
            v2Api
                .previewImport(ImportPreviewRequest(url = url, useAi = useAi))
                .data
                .toV1()
                .toPreview(url)
        } else {
            api.importProject(url = url).toPreview(sourceUrl = url)
        }
}

internal fun ProjectImportResponseDto.toPreview(sourceUrl: String): ImportedProjectPreview {
    val importedName = name.orEmpty().trim().ifBlank { title.orEmpty().trim() }
    val projectName = importedName.ifBlank { "Imported project" }
    val importedSteps = steps.mapIndexed { index, step ->
        StepWriteRequest(
            title = step.title.orEmpty().trim().ifBlank { "Step ${index + 1}" },
            description = step.description?.trim()?.ifBlank { null },
            stepNumber = step.stepNumber ?: index + 1,
            imageUrls = step.images,
        )
    }
    val details =
        listOfNotNull(
            designer.clean()?.let { ImportDetail(ImportDetailKind.Designer, it) },
            sizes.clean()?.let { ImportDetail(ImportDetailKind.Sizes, it) },
            difficulty.clean()?.let { ImportDetail(ImportDetailKind.Difficulty, it) },
        )

    return ImportedProjectPreview(
        sourceUrl = sourceUrl,
        request =
            ProjectWriteRequest(
                name = projectName,
                category = category?.trim()?.ifBlank { null },
                needles = needles?.trim()?.ifBlank { null },
                stitchSample = stitchSample?.trim()?.ifBlank { null },
                description = description?.trim()?.ifBlank { null },
                notes = notes?.trim()?.ifBlank { null },
                otherMaterials = otherMaterials?.trim()?.ifBlank { null },
                tags = tags.map { it.trim() }.filter { it.isNotEmpty() },
                link = link.orEmpty().trim().ifBlank { sourceUrl },
                isAiEnhanced = isAiEnhanced,
                steps = importedSteps,
                // The review step starts with the first few pictures selected.
                imageUrls = imageUrls.take(PRESELECTED_IMAGES),
            ),
        imageUrls = imageUrls,
        aiFallback = aiFallback,
        details = details,
        warnings = warnings,
        aiFields = aiFields,
    )
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }

/** Pictures that start out selected in the confirmation step. */
const val PRESELECTED_IMAGES = 3
