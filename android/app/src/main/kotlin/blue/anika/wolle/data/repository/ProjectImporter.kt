package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ImportAssetDto
import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import kotlinx.serialization.Serializable

/** A fact from the pattern page that has no field of its own and is kept in the project notes. */
enum class ImportDetailKind {
    Designer,
    Sizes,
    Difficulty,
}

@Serializable data class ImportDetail(val kind: ImportDetailKind, val value: String)

/**
 * A yarn the pattern page needs, as the server cleaned and enriched it. [suggestedStashId] is the
 * server's best match among the user's own yarns (brand and name, normalised, fuzzy), if any.
 */
@Serializable
data class ImportedYarn(
    val name: String,
    val brand: String? = null,
    val colorway: String? = null,
    val amount: String? = null,
    val fiberContent: String? = null,
    val weightCategory: String? = null,
    val weightGrams: Int? = null,
    val lengthMeters: Int? = null,
    val link: String? = null,
    val imageUrl: String? = null,
    val notes: String? = null,
    val suggestedStashId: Int? = null,
)

/** Data that can be confirmed and then written through the normal offline project outbox. */
@Serializable
data class ImportedProjectPreview(
    val sourceUrl: String,
    val request: ProjectWriteRequest,
    val imageUrls: List<String>,
    /** Diagrams and PDFs found on the page; all start out selected (see [request]). */
    val assets: List<ImportAssetDto> = emptyList(),
    val aiFallback: Boolean,
    /** Designer, sizes and difficulty; the caller turns them into localized note lines. */
    val details: List<ImportDetail> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Field paths (`project.needles`) the AI model supplied; shown as a hint, not saved. */
    val aiFields: List<String> = emptyList(),
    /** The yarns the page needs; the review picks a stash yarn, a new one or none for each. */
    val yarns: List<ImportedYarn> = emptyList(),
) {
    /** The same preview with only [selected] pictures attached when it is saved. */
    fun withImages(selected: List<String>): ImportedProjectPreview =
        copy(request = request.copy(imageUrls = selected))

    /** The same preview with only the diagrams and PDFs whose URL is in [selected] attached. */
    fun withAssets(selected: List<String>): ImportedProjectPreview =
        copy(request = request.copy(attachmentUrls = assets.filter { it.url in selected }))
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
                attachmentUrls = assets,
            ),
        imageUrls = imageUrls,
        assets = assets,
        aiFallback = aiFallback,
        details = details,
        warnings = warnings,
        aiFields = aiFields,
        yarns = importedYarns(),
    )
}

internal fun ProjectImportResponseDto.importedYarns(): List<ImportedYarn> {
    val suggestions = yarnMatches.associate { it.index to it.yarnId }
    return yarns.mapIndexedNotNull { index, yarn ->
        val name = yarn.name.clean() ?: return@mapIndexedNotNull null
        ImportedYarn(
            name = name,
            brand = yarn.brand.clean(),
            colorway = yarn.colorway.clean(),
            amount = yarn.amount.clean(),
            fiberContent = yarn.fiberContent.clean(),
            weightCategory = yarn.weightCategory.clean(),
            weightGrams = yarn.weight.leadingNumber(),
            lengthMeters = yarn.length.leadingNumber(),
            link = yarn.link.clean(),
            imageUrl = yarn.imageUrl.clean(),
            notes = yarn.notes.clean(),
            suggestedStashId = suggestions[index],
        )
    }
}

/** "50 g" -> 50; text that is not a plain number with a unit is not a measurement. */
private fun String?.leadingNumber(): Int? =
    this?.trim()
        ?.let {
            Regex("^(\\d{1,6})\\s*(?:g|gram|grams|m|meter|meters)?$", RegexOption.IGNORE_CASE)
                .find(it)
        }
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }

/** Pictures that start out selected in the confirmation step. */
const val PRESELECTED_IMAGES = 3
