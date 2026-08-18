package blue.anika.wolle.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose destinations (see MainActivity/WolleNavHost). */
sealed interface Route {
    @Serializable data object Home : Route

    @Serializable data object Projects : Route

    @Serializable data object Yarns : Route

    @Serializable data object Search : Route

    @Serializable data object Settings : Route
}

/** The five bottom-navigation destinations, in display order (see android/TODO.md SNA-5/SNA-9). */
enum class TopLevelDestination(val route: Route, val label: String, val icon: ImageVector) {
    HOME(Route.Home, "Home", Icons.Filled.Home),
    PROJECTS(Route.Projects, "Projects", Icons.Filled.Folder),
    YARNS(Route.Yarns, "Yarn Stash", Icons.Filled.Palette),
    SEARCH(Route.Search, "Search", Icons.Filled.Search),
    SETTINGS(Route.Settings, "Settings", Icons.Filled.Settings),
}
