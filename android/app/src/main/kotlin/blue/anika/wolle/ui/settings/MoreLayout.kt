package blue.anika.wolle.ui.settings

/** A row of the More screen's first card that is not a settings category. */
sealed interface MoreLink {
    data object Create : MoreLink

    data object Gauge : MoreLink

    data class Category(val category: SettingsCategory) : MoreLink
}

/**
 * The More screen's order, like the website's More page: Create, Categories, Gauge calculator,
 * (Admin for administrators), About, then the Account row, the Android-only Settings group and
 * finally Sign out (always last, drawn by the screen).
 */
data class MoreLayout(val links: List<MoreLink>, val settings: List<SettingsCategory>)

fun moreLayout(isAdmin: Boolean): MoreLayout =
    MoreLayout(
        links =
            listOfNotNull(
                MoreLink.Create,
                MoreLink.Category(SettingsCategory.Categories),
                MoreLink.Gauge,
                MoreLink.Category(SettingsCategory.Admin).takeIf { isAdmin },
                MoreLink.Category(SettingsCategory.About),
            ),
        settings =
            listOf(
                SettingsCategory.Appearance,
                SettingsCategory.Navigation,
                SettingsCategory.DetailCards,
                SettingsCategory.Sync,
                SettingsCategory.Backup,
            ),
    )
