package blue.anika.wolle.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose destinations (see MainActivity/StricknaniNavHost). */
sealed interface Route {
    @Serializable data object Onboarding : Route

    @Serializable data object Home : Route

    @Serializable data object Projects : Route

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
 * `NavbarCustomization.sanitize`'s "missing destination" handling appends it as visible for
 * anyone with an already-saved navbar preference, same as any other newly added destination.
 */
enum class TopLevelDestination(val route: Route, val label: String, val icon: ImageVector) {
    HOME(Route.Home, "Home", Icons.Filled.Home),
    PROJECTS(Route.Projects, "Projects", Icons.Filled.Folder),
    YARNS(Route.Yarns, "Yarns", Icons.Filled.Checkroom),
    SEARCH(Route.Search, "Search", Icons.Filled.Search),
    GAUGE(Route.Gauge, "Gauge", Icons.Filled.Calculate),
    SETTINGS(Route.Settings, "Settings", Icons.Filled.Settings),
}
