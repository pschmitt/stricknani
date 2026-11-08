package blue.anika.wolle.ui.create

import androidx.annotation.StringRes
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ImportAvailability

/** The four tiles of the Create screen, in the website's order. */
enum class CreateKind {
    NewProject,
    NewYarn,
    ImportProject,
    ImportYarn,
}

/**
 * One Create tile. [disabledReasonRes] set means the tile cannot be used on this server; [noteRes]
 * is a softer limit (the tile works, one part of it does not). Mirrors `create/+page.svelte`.
 */
data class CreateOption(
    val kind: CreateKind,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val disabledReasonRes: Int? = null,
    @StringRes val noteRes: Int? = null,
) {
    val enabled: Boolean
        get() = disabledReasonRes == null
}

/**
 * The tiles for what the server lets this session import. [importsOffered] false (local-only mode)
 * drops the import tiles instead of greying them out.
 */
fun createOptions(
    availability: ImportAvailability,
    importsOffered: Boolean = true,
): List<CreateOption> =
    listOf(
            CreateOption(
                CreateKind.NewProject,
                R.string.project_new_label,
                R.string.more_create_project_subtitle,
            ),
            CreateOption(
                CreateKind.NewYarn,
                R.string.yarns_list_new_yarn_fab,
                R.string.more_create_yarn_subtitle,
            ),
            CreateOption(
                CreateKind.ImportProject,
                R.string.create_import_project_title,
                R.string.create_import_project_description,
                noteRes = R.string.import_unavailable_ai.takeUnless { availability.ai },
            ),
            CreateOption(
                CreateKind.ImportYarn,
                R.string.create_import_yarn_title,
                R.string.create_import_yarn_description,
                disabledReasonRes = R.string.import_unavailable_url.takeUnless { availability.url },
                noteRes = R.string.import_unavailable_ai.takeUnless { availability.ai },
            ),
        )
        .filter {
            importsOffered ||
                it.kind != CreateKind.ImportProject && it.kind != CreateKind.ImportYarn
        }
