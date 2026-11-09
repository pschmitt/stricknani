package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ImportedYarn

/** What to do with a yarn the import found. */
enum class ImportYarnChoice {
    /** Link a yarn from the user's own stash. */
    Stash,
    /** Create a new yarn from the extracted details; it is created and linked with the project. */
    New,
    /** Leave the yarn out. */
    Skip,
}

/** One imported yarn with the user's decision. [yarn] holds the (editable) details for [New]. */
data class ImportYarnReview(
    val yarn: ImportedYarn,
    val choice: ImportYarnChoice,
    val stashId: Int? = null,
)

/**
 * The review of an import before it is saved: a decision per imported yarn, extra stash yarns the
 * page did not name, and the single notes text (it starts with the extracted details that have no
 * field of their own). Pure data, so the rules are unit-testable without Compose.
 */
data class ProjectImportReview(
    val yarns: List<ImportYarnReview>,
    val extraStashIds: List<Int> = emptyList(),
    val notes: String = "",
) {
    /** Switches yarn [index] to [choice]. "Stash" without a picked yarn waits for a pick. */
    fun choose(index: Int, choice: ImportYarnChoice): ProjectImportReview =
        updateYarn(index) { it.copy(choice = choice) }

    /** Links stash yarn [stashId] for yarn [index] (and selects the stash choice). */
    fun pickStash(index: Int, stashId: Int): ProjectImportReview =
        updateYarn(index) { it.copy(choice = ImportYarnChoice.Stash, stashId = stashId) }

    fun editYarn(index: Int, edit: (ImportedYarn) -> ImportedYarn): ProjectImportReview =
        updateYarn(index) { it.copy(yarn = edit(it.yarn)) }

    fun addExtra(stashId: Int): ProjectImportReview =
        if (stashId in extraStashIds) this else copy(extraStashIds = extraStashIds + stashId)

    fun removeExtra(stashId: Int): ProjectImportReview =
        copy(extraStashIds = extraStashIds - stashId)

    fun withNotes(value: String): ProjectImportReview = copy(notes = value)

    /** Stash yarns to link: the chosen ones first, then the extra ones, each once. */
    val linkedStashIds: List<Int>
        get() =
            (yarns.filter { it.choice == ImportYarnChoice.Stash }.mapNotNull { it.stashId } +
                    extraStashIds)
                .distinct()

    /**
     * [base] with the review applied: linked stash yarns, the yarns to create (the server creates
     * and links them together with the project, so nothing is saved separately and nothing is left
     * behind when the project cannot be saved) and the notes.
     */
    fun applyTo(base: ProjectWriteRequest, amountLabel: String): ProjectWriteRequest =
        base.copy(
            yarnIds = linkedStashIds,
            newYarns = newYarns(amountLabel),
            notes = notes.trim().ifBlank { null },
        )

    /** The yarns to create with the project, as write requests. */
    fun newYarns(amountLabel: String): List<YarnWriteRequest> =
        yarns
            .filter { it.choice == ImportYarnChoice.New && it.yarn.name.isNotBlank() }
            .map { it.yarn.toWriteRequest(amountLabel) }

    private fun updateYarn(
        index: Int,
        change: (ImportYarnReview) -> ImportYarnReview,
    ): ProjectImportReview =
        if (index !in yarns.indices) this
        else copy(yarns = yarns.mapIndexed { i, yarn -> if (i == index) change(yarn) else yarn })

    companion object {
        /**
         * Starts the review: a yarn with a suggested stash match is preselected as "use my yarn",
         * every other one as "create". [notes] is the prefilled leftover-details text.
         */
        fun from(preview: ImportedProjectPreview, notes: String): ProjectImportReview =
            ProjectImportReview(
                yarns =
                    preview.yarns.map { yarn ->
                        ImportYarnReview(
                            yarn = yarn,
                            choice =
                                if (yarn.suggestedStashId != null) ImportYarnChoice.Stash
                                else ImportYarnChoice.New,
                            stashId = yarn.suggestedStashId,
                        )
                    },
                notes = notes,
            )
    }
}

private fun ImportedYarn.toWriteRequest(amountLabel: String): YarnWriteRequest =
    YarnWriteRequest(
        name = name.trim(),
        brand = brand?.trim()?.ifBlank { null },
        colorway = colorway?.trim()?.ifBlank { null },
        fiberContent = fiberContent?.trim()?.ifBlank { null },
        weightCategory = weightCategory?.trim()?.ifBlank { null },
        weightGrams = weightGrams,
        lengthMeters = lengthMeters,
        notes =
            listOfNotNull(
                    notes?.trim()?.ifBlank { null },
                    amount?.trim()?.ifBlank { null }?.let { "$amountLabel: $it" },
                )
                .joinToString("\n\n")
                .ifBlank { null },
        link = link?.trim()?.ifBlank { null },
        isAiEnhanced = false,
        imageUrls = listOfNotNull(imageUrl?.trim()?.ifBlank { null }),
    )
