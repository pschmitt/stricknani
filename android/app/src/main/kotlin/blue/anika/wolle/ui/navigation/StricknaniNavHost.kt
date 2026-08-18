package blue.anika.wolle.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PlaceholderScreen
import blue.anika.wolle.ui.onboarding.OnboardingScreen
import blue.anika.wolle.ui.settings.SettingsScreen

/**
 * The app's main scaffold: a Material 3 bottom navigation bar switching between the five
 * top-level destinations. Most destinations are still a [PlaceholderScreen] - real screens land
 * in SNA-9/SNA-10; the configurable navbar (reordering/hiding items) is SNA-16.
 *
 * @param startDestination [Route.Onboarding] until a server URL + API token are saved, otherwise
 *   [Route.Home] - `MainActivity` picks this reactively from
 *   `SettingsRepository.isConfigured` and recreates this whole composable (a fresh
 *   `NavController`) when it flips, rather than this composable navigating between the two
 *   itself.
 */
@Composable
fun StricknaniNavHost(modifier: Modifier = Modifier, startDestination: Route = Route.Home) {
    val navController = rememberNavController()

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (startDestination != Route.Onboarding) {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any {
                                it.hasRoute(destination.route::class)
                            } ?: false
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<Route.Onboarding> { OnboardingScreen() }
            composable<Route.Home> {
                PlaceholderScreen(
                    icon = TopLevelDestination.HOME.icon,
                    title = stringResource(R.string.placeholder_home_title),
                    subtitle = stringResource(R.string.placeholder_home_subtitle),
                )
            }
            composable<Route.Projects> {
                PlaceholderScreen(
                    icon = TopLevelDestination.PROJECTS.icon,
                    title = stringResource(R.string.placeholder_projects_title),
                    subtitle = stringResource(R.string.placeholder_projects_subtitle),
                )
            }
            composable<Route.Yarns> {
                PlaceholderScreen(
                    icon = TopLevelDestination.YARNS.icon,
                    title = stringResource(R.string.placeholder_yarns_title),
                    subtitle = stringResource(R.string.placeholder_yarns_subtitle),
                )
            }
            composable<Route.Search> {
                PlaceholderScreen(
                    icon = TopLevelDestination.SEARCH.icon,
                    title = stringResource(R.string.placeholder_search_title),
                    subtitle = stringResource(R.string.placeholder_search_subtitle),
                )
            }
            composable<Route.Settings> { SettingsScreen() }
        }
    }
}
