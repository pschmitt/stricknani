package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.api.v2.ImportDraftYarnDto
import blue.anika.wolle.data.api.v2.ImportPreviewDto
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
