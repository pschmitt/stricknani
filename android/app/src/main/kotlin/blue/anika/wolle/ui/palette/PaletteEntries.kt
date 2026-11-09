package blue.anika.wolle.ui.palette

import androidx.annotation.StringRes
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.navigation.TopLevelDestination
import blue.anika.wolle.ui.settings.SettingsCategory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Builds the palette's entries from the real navigation and settings definitions. */
object PaletteEntries {
    /** Settings categories listed as pages (the website lists the same four as pages). */
    private val pageCategories =
        listOf(
            SettingsCategory.Account,
            SettingsCategory.Backup,
            SettingsCategory.Admin,
            SettingsCategory.About,
        )

    /** Categories that are not a settings entry of their own: pages or listed elsewhere. */
    private val notSettings = pageCategories + SettingsCategory.Categories

    /** Sections inside a settings screen: title, the screen that holds them, extra words. */
    private data class Section(
        @StringRes val title: Int,
        val route: Route,
        val keywords: List<String>,
        val icon: PaletteIcon,
    )

    private val sections =
        listOf(
            Section(
                R.string.appearance_settings_theme_title,
                Route.SettingsCategoryRoute(SettingsCategory.Appearance.name),
                listOf("theme dark light mode night"),
                PaletteIcon.Appearance,
            ),
            Section(
                R.string.appearance_settings_colors_title,
                Route.SettingsCategoryRoute(SettingsCategory.Appearance.name),
                listOf("colors colours wallpaper dynamic"),
                PaletteIcon.Appearance,
            ),
            Section(
                R.string.settings_project_timers_title,
                Route.SettingsCategoryRoute(SettingsCategory.Interface.name),
                listOf("stopwatch time reminder"),
                PaletteIcon.Timer,
            ),
            Section(
                R.string.settings_notifications_title,
                Route.Settings,
                listOf("push alerts"),
                PaletteIcon.Notifications,
            ),
            Section(
                R.string.settings_detail_cards_project_title,
                Route.SettingsCategoryRoute(SettingsCategory.DetailCards.name),
                listOf("order sections"),
                PaletteIcon.DetailCards,
            ),
            Section(
                R.string.settings_detail_cards_yarn_title,
                Route.SettingsCategoryRoute(SettingsCategory.DetailCards.name),
                listOf("order sections"),
                PaletteIcon.DetailCards,
            ),
            Section(
                R.string.backup_settings_scheduled_title,
                Route.SettingsCategoryRoute(SettingsCategory.Backup.name),
                listOf("backup export automatic"),
                PaletteIcon.Backup,
            ),
            Section(
                R.string.backup_settings_manual_title,
                Route.SettingsCategoryRoute(SettingsCategory.Backup.name),
                listOf("backup export restore download your data"),
                PaletteIcon.Backup,
            ),
        )

    /** Actions, pages and settings: everything that does not come from the database. */
    fun static(isAdmin: Boolean, url: String?, s: (Int) -> String): List<PaletteEntry> {
        val out = ArrayList<PaletteEntry>()
        if (url != null) {
            out +=
                PaletteEntry(
                    id = "action:import-project",
                    group = PaletteGroup.Actions,
                    title = s(R.string.palette_action_import_pattern),
                    sub = url,
                    icon = PaletteIcon.Import,
                    target = PaletteTarget.Go(Route.ProjectEditor(importUrl = url)),
                )
            out +=
                PaletteEntry(
                    id = "action:import-yarn",
                    group = PaletteGroup.Actions,
                    title = s(R.string.palette_action_import_yarn),
                    sub = url,
                    icon = PaletteIcon.Yarns,
                    target = PaletteTarget.Go(Route.YarnEditor(importUrl = url)),
                )
        }
        out +=
            PaletteEntry(
                id = "action:new-project",
                group = PaletteGroup.Actions,
                title = s(R.string.palette_action_new_project),
                keywords = listOf("create add knit"),
                icon = PaletteIcon.Add,
                target = PaletteTarget.Go(Route.ProjectEditor()),
            )
        out +=
            PaletteEntry(
                id = "action:new-yarn",
                group = PaletteGroup.Actions,
                title = s(R.string.palette_action_new_yarn),
                keywords = listOf("create add stash"),
                icon = PaletteIcon.Add,
                target = PaletteTarget.Go(Route.YarnEditor()),
            )
        out +=
            PaletteEntry(
                id = "action:toggle-theme",
                group = PaletteGroup.Actions,
                title = s(R.string.palette_action_toggle_dark),
                keywords = listOf("theme light night appearance"),
                icon = PaletteIcon.DarkMode,
                target = PaletteTarget.ToggleDarkMode,
            )

        // Pages: the bottom-bar destinations (Home, Projects, Yarns, Categories, Gauge), with
        // Create after Yarns as on the website.
        fun page(id: String, @StringRes title: Int, icon: PaletteIcon, route: Route) =
            PaletteEntry(
                id = "page:$id",
                group = PaletteGroup.Pages,
                title = s(title),
                icon = icon,
                target = PaletteTarget.Go(route),
            )
        for (destination in TopLevelDestination.entries) {
            val (icon, title) =
                when (destination) {
                    TopLevelDestination.HOME -> PaletteIcon.Home to destination.labelRes
                    TopLevelDestination.PROJECTS -> PaletteIcon.Projects to destination.labelRes
                    TopLevelDestination.YARNS -> PaletteIcon.Yarns to destination.labelRes
                    TopLevelDestination.CATEGORIES -> PaletteIcon.Categories to destination.labelRes
                    TopLevelDestination.GAUGE ->
                        PaletteIcon.Gauge to R.string.gauge_calculator_title
                    // More and the Search tab are not pages of their own here.
                    TopLevelDestination.SETTINGS,
                    TopLevelDestination.SEARCH -> continue
                }
            if (destination == TopLevelDestination.CATEGORIES) {
                out += page("create", R.string.more_create_title, PaletteIcon.Create, Route.Create)
            }
            out += page(destination.name, title, icon, destination.route)
        }
        for (category in pageCategories) {
            if (category == SettingsCategory.Admin && !isAdmin) continue
            out +=
                page(
                    "settings-${category.name}",
                    category.titleRes,
                    category.paletteIcon(),
                    Route.SettingsCategoryRoute(category.name),
                )
        }

        for (category in SettingsCategory.entries) {
            if (category in notSettings) continue
            out +=
                PaletteEntry(
                    id = "setting:${category.name}",
                    group = PaletteGroup.Settings,
                    title = s(category.titleRes),
                    sub = s(category.subtitleRes),
                    icon = category.paletteIcon(),
                    target = PaletteTarget.Go(Route.SettingsCategoryRoute(category.name)),
                )
        }
        for (section in sections) {
            out +=
                PaletteEntry(
                    id = "setting:section:${section.title}",
                    group = PaletteGroup.Settings,
                    title = s(section.title),
                    keywords = section.keywords,
                    icon = section.icon,
                    target = PaletteTarget.Go(section.route),
                )
        }
        return out
    }

    private val tagsSerializer = ListSerializer(String.serializer())

    fun projects(rows: List<ProjectEntity>, previewUrl: (String?) -> String?): List<PaletteEntry> =
        rows.map { row ->
            val tags =
                runCatching { Json.decodeFromString(tagsSerializer, row.tagsJson) }
                    .getOrDefault(emptyList())
            PaletteEntry(
                id = "project:${row.id}",
                group = PaletteGroup.Projects,
                title = row.name,
                sub = row.category,
                keywords = tags,
                icon = PaletteIcon.Projects,
                target = PaletteTarget.Go(Route.ProjectDetail(row.id)),
                previewUrl = previewUrl(row.previewUrl),
            )
        }

    fun yarns(rows: List<YarnEntity>, previewUrl: (String?) -> String?): List<PaletteEntry> =
        rows.map { row ->
            PaletteEntry(
                id = "yarn:${row.id}",
                group = PaletteGroup.Yarns,
                title = row.name,
                sub = listOfNotNull(row.brand, row.colorway).joinToString(" · ").ifBlank { null },
                keywords = listOfNotNull(row.weightCategory),
                icon = PaletteIcon.Yarns,
                target = PaletteTarget.Go(Route.YarnDetail(row.id)),
                previewUrl = previewUrl(row.previewUrl),
            )
        }

    fun categories(rows: List<CategoryEntity>): List<PaletteEntry> = rows.map { row ->
        PaletteEntry(
            id = "category:${row.id}",
            group = PaletteGroup.Categories,
            title = row.name,
            icon = PaletteIcon.Categories,
            target = PaletteTarget.Go(Route.Categories),
        )
    }

    private fun SettingsCategory.paletteIcon(): PaletteIcon =
        when (this) {
            SettingsCategory.Account -> PaletteIcon.Account
            SettingsCategory.Language -> PaletteIcon.Language
            SettingsCategory.Appearance -> PaletteIcon.Appearance
            SettingsCategory.Interface -> PaletteIcon.Layout
            SettingsCategory.Navigation -> PaletteIcon.BottomBar
            SettingsCategory.Categories -> PaletteIcon.Categories
            SettingsCategory.DetailCards -> PaletteIcon.DetailCards
            SettingsCategory.Sync -> PaletteIcon.Sync
            SettingsCategory.Backup -> PaletteIcon.Backup
            SettingsCategory.Admin -> PaletteIcon.Admin
            SettingsCategory.About -> PaletteIcon.About
        }
}
