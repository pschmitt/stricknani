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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import blue.anika.wolle.ui.gauge.GaugeCalculatorScreen
import blue.anika.wolle.ui.home.HomeScreen
import blue.anika.wolle.ui.onboarding.OnboardingScreen
import blue.anika.wolle.ui.projects.ProjectDetailScreen
import blue.anika.wolle.ui.projects.ProjectEditorScreen
import blue.anika.wolle.ui.projects.ProjectsListScreen
import blue.anika.wolle.ui.search.SearchScreen
import blue.anika.wolle.ui.settings.SettingsScreen
import blue.anika.wolle.ui.yarns.YarnDetailScreen
import blue.anika.wolle.ui.yarns.YarnEditorScreen
import blue.anika.wolle.ui.yarns.YarnsListScreen

/**
 * The app's main scaffold: a Material 3 bottom navigation bar switching between the five top-level
 * destinations, plus non-top-level detail routes (`ProjectDetail`/`YarnDetail`) and create/edit
 * routes (`ProjectEditor`/`YarnEditor`, SNA-10). The configurable navbar (reordering/hiding items)
 * is SNA-16.
 *
 * @param startDestination [Route.Onboarding] until a server URL + API token are saved, otherwise
 *   [Route.Home] - `MainActivity` picks this reactively from `SettingsRepository.isConfigured` and
 *   recreates this whole composable (a fresh `NavController`) when it flips, rather than this
 *   composable navigating between the two itself.
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
                HomeScreen(
                    onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                    onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                    onGaugeClick = { navController.navigate(Route.Gauge) },
                )
            }
            composable<Route.Projects> {
                ProjectsListScreen(
                    onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                    onAddProjectClick = { navController.navigate(Route.ProjectEditor()) },
                )
            }
            composable<Route.Yarns> {
                YarnsListScreen(
                    onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                    onAddYarnClick = { navController.navigate(Route.YarnEditor()) },
                )
            }
            composable<Route.Search> {
                SearchScreen(
                    onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                    onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                )
            }
            composable<Route.Settings> { SettingsScreen() }
            composable<Route.ProjectDetail> {
                ProjectDetailScreen(
                    onBack = { navController.navigateUp() },
                    onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                    onEditClick = { id -> navController.navigate(Route.ProjectEditor(id)) },
                )
            }
            composable<Route.YarnDetail> {
                YarnDetailScreen(
                    onBack = { navController.navigateUp() },
                    onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                    onEditClick = { id -> navController.navigate(Route.YarnEditor(id)) },
                )
            }
            composable<Route.ProjectEditor> {
                ProjectEditorScreen(
                    onSaved = { navController.navigateUp() },
                    onDeleted = {
                        navController.navigate(Route.Projects) {
                            popUpTo(Route.Projects) { inclusive = true }
                        }
                    },
                    onBack = { navController.navigateUp() },
                )
            }
            composable<Route.YarnEditor> {
                YarnEditorScreen(
                    onSaved = { navController.navigateUp() },
                    onDeleted = {
                        navController.navigate(Route.Yarns) {
                            popUpTo(Route.Yarns) { inclusive = true }
                        }
                    },
                    onBack = { navController.navigateUp() },
                )
            }
            composable<Route.Gauge> {
                GaugeCalculatorScreen(onBack = { navController.navigateUp() })
            }
        }
    }
}
