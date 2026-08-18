package blue.anika.wolle.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
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
}

/** The five bottom-navigation destinations, in display order (see android/TODO.md SNA-5/SNA-9). */
enum class TopLevelDestination(val route: Route, val label: String, val icon: ImageVector) {
    HOME(Route.Home, "Home", Icons.Filled.Home),
    PROJECTS(Route.Projects, "Projects", Icons.Filled.Folder),
    YARNS(Route.Yarns, "Yarns", Icons.Filled.Palette),
    SEARCH(Route.Search, "Search", Icons.Filled.Search),
    SETTINGS(Route.Settings, "Settings", Icons.Filled.Settings),
}
