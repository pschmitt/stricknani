package blue.anika.wolle.ui.settings

/** A row of the More screen's first card that is not a settings category. */
sealed interface MoreLink {
    data object Create : MoreLink

    data object Gauge : MoreLink

    data class Category(val category: SettingsCategory) : MoreLink
}

/**
 * The More screen's order: Create, Categories, Gauge calculator; then the Settings group (Account
 * settings first, drawn by the screen, then the Android-only [settings]); then [footer] (Admin for
 * administrators, About last). Sign out lives only in Account settings.
 */
data class MoreLayout(
    val links: List<MoreLink>,
    val settings: List<SettingsCategory>,
    val footer: List<SettingsCategory>,
)

fun moreLayout(isAdmin: Boolean): MoreLayout =
    MoreLayout(
        links =
            listOf(
                MoreLink.Create,
                MoreLink.Category(SettingsCategory.Categories),
                MoreLink.Gauge,
            ),
        settings =
            listOf(
                SettingsCategory.Appearance,
                SettingsCategory.Navigation,
                SettingsCategory.DetailCards,
                SettingsCategory.Sync,
                SettingsCategory.Backup,
            ),
        footer = listOfNotNull(SettingsCategory.Admin.takeIf { isAdmin }, SettingsCategory.About),
    )
