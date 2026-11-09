package blue.anika.wolle.ui.create

import androidx.annotation.StringRes
import blue.anika.wolle.R

/** The tiles of the Create screen, in the website's order: one way to add things. */
enum class CreateKind {
    NewProject,
    NewYarn,
}

/** One Create tile. Mirrors `create/+page.svelte`. */
data class CreateOption(
    val kind: CreateKind,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
)

/** Which imports the app offers right now (both false in local-only mode). */
data class ImportOffers(val project: Boolean = false, val yarn: Boolean = false) {
    val any: Boolean
        get() = project || yarn
}

/**
 * "New project" and "New yarn". Importing a link is not a tile of its own: the new project and the
 * new yarn editors have a card for it at the top. The descriptions mention importing only when
 * [offers] says it is available, like the web's texts.
 */
fun createOptions(offers: ImportOffers = ImportOffers()): List<CreateOption> =
    listOf(
        CreateOption(
            CreateKind.NewProject,
            R.string.project_new_label,
            if (offers.project) R.string.create_project_subtitle_import
            else R.string.more_create_project_subtitle,
        ),
        CreateOption(
            CreateKind.NewYarn,
            R.string.yarns_list_new_yarn_fab,
            if (offers.yarn) R.string.create_yarn_subtitle_import
            else R.string.more_create_yarn_subtitle,
        ),
    )

/** The subtitle of the Create card on Home and in More. */
@StringRes
fun moreCreateSubtitleRes(offers: ImportOffers, localMode: Boolean): Int =
    when {
        offers.any -> R.string.more_create_subtitle_import
        localMode -> R.string.more_create_subtitle_local
        else -> R.string.more_create_subtitle
    }
