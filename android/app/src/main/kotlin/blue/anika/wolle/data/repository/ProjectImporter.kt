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

/** Data that can be confirmed and then written through the normal offline project outbox. */
data class ImportedProjectPreview(
    val sourceUrl: String,
    val request: ProjectWriteRequest,
    val imageUrls: List<String>,
    val aiFallback: Boolean,
)

/** Boundary for importing project data, kept small so the UI state machine is unit-testable. */
interface ProjectImporter {
    suspend fun importFromUrl(url: String): ImportedProjectPreview
}

/**
 * Imports through `POST /api/v2/imports/preview` (SNA-87), or the web `POST /projects/import` route
 * on a server without v2.
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
            v2Api.previewImport(ImportPreviewRequest(url = url)).data.toV1().toPreview(url)
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
                imageUrls = imageUrls,
            ),
        imageUrls = imageUrls,
        aiFallback = aiFallback,
    )
}
