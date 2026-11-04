package blue.anika.wolle.ui.settings

/** A row of the More screen's first card that is not a settings category. */
sealed interface MoreLink {
    data object Create : MoreLink

    data object Gauge : MoreLink

    data class Category(val category: SettingsCategory) : MoreLink
}

/**
 * The More screen's order: Create, Categories, Gauge calculator; then the Settings group (Account
 * settings first, drawn by the screen, then the Android-only [settings]); the Data group ([data]:
 * keeping up to date, backup and Admin for administrators); then [footer], About on its own at the
 * bottom. Sign out lives only in Account settings.
 */
data class MoreLayout(
    val links: List<MoreLink>,
    val settings: List<SettingsCategory>,
    val data: List<SettingsCategory>,
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
            ),
        data =
            listOfNotNull(
                SettingsCategory.Sync,
                SettingsCategory.Backup,
                SettingsCategory.Admin.takeIf { isAdmin },
            ),
        footer = listOf(SettingsCategory.About),
    )
