package blue.anika.wolle.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.MdiIcons
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose destinations (see MainActivity/StricknaniNavHost). */
sealed interface Route {
    @Serializable data object Onboarding : Route

    @Serializable data object Home : Route

    @Serializable data object Projects : Route

    @Serializable data object Categories : Route

    @Serializable data object Yarns : Route

    @Serializable data object Search : Route

    @Serializable data object Settings : Route

    @Serializable data class ProjectDetail(val projectId: Int) : Route

    @Serializable data class YarnDetail(val yarnId: Int) : Route

    /** `projectId == null` is create; a value is edit. */
    @Serializable data class ProjectEditor(val projectId: Int? = null) : Route

    /** `yarnId == null` is create; a value is edit. */
    @Serializable data class YarnEditor(val yarnId: Int? = null) : Route

    /** Gauge calculator (SNA-11) - pure math, reachable from `HomeScreen`'s top bar. */
    @Serializable data object Gauge : Route

    /** Open-source libraries used (SNA-34) - reachable from Settings' About category. */
    @Serializable data object Libraries : Route

    /**
     * A Settings sub-screen (SNA-21). [categoryName] is `SettingsCategory.name` (a plain `String`
     * rather than the enum itself, to sidestep any Navigation Compose enum-as-route-arg quirks).
     */
    @Serializable data class SettingsCategoryRoute(val categoryName: String) : Route
}

/**
 * The customizable bottom-navigation destinations, in default display order (see android/TODO.md
 * SNA-5/SNA-9/SNA-16). [GAUGE] (SNA-11's calculator, previously only reachable via `HomeScreen`'s
 * top bar icon) was added as a navbar option per user feedback (2026-08-18) -
 * `NavbarCustomization.sanitize`'s "missing destination" handling appends it as visible for anyone
 * with an already-saved navbar preference, same as any other newly added destination.
 */
enum class TopLevelDestination(
    val route: Route,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val routeTypes: Set<KClass<out Route>>,
) {
    HOME(
        Route.Home,
        R.string.destination_label_home,
        Icons.Filled.Home,
        setOf(Route.Home::class),
    ),
    PROJECTS(
        Route.Projects,
        R.string.destination_label_projects,
        Icons.Filled.Folder,
        setOf(Route.Projects::class, Route.ProjectDetail::class, Route.ProjectEditor::class),
    ),
    CATEGORIES(
        Route.Categories,
        R.string.destination_label_categories,
        Icons.Filled.Category,
        setOf(Route.Categories::class),
    ),
    YARNS(
        Route.Yarns,
        R.string.destination_label_yarns,
        MdiIcons.Sheep,
        setOf(Route.Yarns::class, Route.YarnDetail::class, Route.YarnEditor::class),
    ),
    SEARCH(
        Route.Search,
        R.string.destination_label_search,
        Icons.Filled.Search,
        setOf(Route.Search::class),
    ),
    GAUGE(
        Route.Gauge,
        R.string.destination_label_gauge,
        Icons.Filled.Calculate,
        setOf(Route.Gauge::class),
    ),
    SETTINGS(
        Route.Settings,
        R.string.settings_root_title,
        Icons.Filled.Settings,
        setOf(Route.Settings::class, Route.SettingsCategoryRoute::class, Route.Libraries::class),
    ),
}

@Composable fun TopLevelDestination.label(): String = stringResource(labelRes)
