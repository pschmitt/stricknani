package blue.anika.wolle.ui.settings

/** A row of the More screen's first card that is not a settings category. */
sealed interface MoreLink {
    data object Create : MoreLink

    data object Gauge : MoreLink

    /** The trash (de: Papierkorb), right after the gauge calculator like the website's More. */
    data object Trash : MoreLink

    /** The signed-in user's own activity (de: Meine Aktivität), right after the trash. */
    data object Activity : MoreLink

    data class Category(val category: SettingsCategory) : MoreLink
}

/**
 * The More screen's order: Create, Categories, Gauge calculator, Trash, My activity; then the
 * Settings group (Account settings first, drawn by the screen, then Language and User interface
 * from [settings]); the Data group ([data]: Admin for administrators, keeping up to date and
 * backup); then [footer], About on its own at the bottom. Sign out lives only in Account settings.
 * [interfacePages] are opened from User interface rather than from More itself.
 */
data class MoreLayout(
    val links: List<MoreLink>,
    val settings: List<SettingsCategory>,
    val data: List<SettingsCategory>,
    val footer: List<SettingsCategory>,
    val interfacePages: List<SettingsCategory> =
        listOf(
            SettingsCategory.Appearance,
            SettingsCategory.Navigation,
            SettingsCategory.DetailCards,
        ),
)

fun moreLayout(isAdmin: Boolean): MoreLayout =
    MoreLayout(
        links =
            listOf(
                MoreLink.Create,
                MoreLink.Category(SettingsCategory.Categories),
                MoreLink.Gauge,
                MoreLink.Trash,
                MoreLink.Activity,
            ),
        settings =
            listOf(
                SettingsCategory.Language,
                SettingsCategory.Interface,
            ),
        data =
            listOfNotNull(
                SettingsCategory.Admin.takeIf { isAdmin },
                SettingsCategory.Sync,
                SettingsCategory.Backup,
            ),
        footer = listOf(SettingsCategory.About),
    )
