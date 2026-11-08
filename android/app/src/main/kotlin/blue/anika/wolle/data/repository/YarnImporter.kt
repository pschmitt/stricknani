package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.api.v2.ImportDraftYarnDto
import blue.anika.wolle.data.api.v2.ImportPreviewDto
import blue.anika.wolle.data.api.v2.ImportPreviewRequest
import blue.anika.wolle.data.api.v2.ImportsV2Api
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

/** A fact from the yarn page that has no field of its own and is kept in the yarn notes. */
enum class YarnImportNoteKind {
    Gauge,
    Care,
    Price,
}

@Serializable data class YarnImportNote(val kind: YarnImportNoteKind, val value: String)

/** A yarn that can be confirmed and then written through the normal offline outbox. */
@Serializable
data class ImportedYarnPreview(
    val sourceUrl: String,
    val request: YarnWriteRequest,
    val imageUrls: List<String>,
    val aiFallback: Boolean,
    val notes: List<YarnImportNote> = emptyList(),
    val warnings: List<String> = emptyList(),
    val aiFields: List<String> = emptyList(),
) {
    fun withImages(selected: List<String>): ImportedYarnPreview =
        copy(request = request.copy(imageUrls = selected))
}

interface YarnImporter {
    suspend fun importFromUrl(url: String): ImportedYarnPreview
}

/** Imports a yarn through `POST /api/v2/imports/preview` with `target: "yarn"`. */
@Singleton
class ApiYarnImporter
@Inject
constructor(private val v2Api: ImportsV2Api, private val capabilities: ApiCapabilities) :
    YarnImporter {
    override suspend fun importFromUrl(url: String): ImportedYarnPreview {
        // Yarn import exists on v2 only; an older server answers 404, which the UI reports.
        capabilities.resolve()
        val useAi = capabilities.current.importsAi
        return v2Api
            .previewImport(ImportPreviewRequest(url = url, useAi = useAi, target = "yarn"))
            .data
            .toYarnPreview(url)
    }
}

internal fun ImportPreviewDto.toYarnPreview(sourceUrl: String): ImportedYarnPreview {
    val yarn = draft.yarn ?: ImportDraftYarnDto(name = draft.project.name)
    val name = yarn.name.clean() ?: "Imported yarn"
    val notes =
        listOfNotNull(
            yarn.gauge.clean()?.let { YarnImportNote(YarnImportNoteKind.Gauge, it) },
            yarn.care.clean()?.let { YarnImportNote(YarnImportNoteKind.Care, it) },
            yarn.price.clean()?.let { YarnImportNote(YarnImportNoteKind.Price, it) },
        )
    return ImportedYarnPreview(
        sourceUrl = sourceUrl,
        request =
            YarnWriteRequest(
                name = name,
                description = yarn.description.clean(),
                brand = yarn.brand.clean(),
                colorway = yarn.colorway.clean(),
                fiberContent = yarn.fiberContent.clean(),
                weightCategory = yarn.weightCategory.clean(),
                recommendedNeedles = yarn.recommendedNeedles.clean(),
                weightGrams = yarn.weightGrams,
                lengthMeters = yarn.lengthMeters,
                link = yarn.link.clean() ?: draft.sourceUrl ?: sourceUrl,
                isAiEnhanced = isAiEnhanced,
                imageUrls = yarn.imageUrls.take(PRESELECTED_IMAGES),
            ),
        imageUrls = yarn.imageUrls,
        aiFallback = aiFallback,
        notes = notes,
        warnings = draft.warnings,
        aiFields = draft.provenance.filterValues { it == "ai" }.keys.toList(),
    )
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
