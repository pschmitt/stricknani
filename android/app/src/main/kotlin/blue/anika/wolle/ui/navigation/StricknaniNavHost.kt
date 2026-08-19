package blue.anika.wolle.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.MutationFeedbackEffect
import blue.anika.wolle.ui.categories.CategoriesScreen
import blue.anika.wolle.ui.gauge.GaugeCalculatorScreen
import blue.anika.wolle.ui.home.HomeScreen
import blue.anika.wolle.ui.onboarding.OnboardingScreen
import blue.anika.wolle.ui.projects.ProjectDetailScreen
import blue.anika.wolle.ui.projects.ProjectEditorScreen
import blue.anika.wolle.ui.projects.ProjectsListScreen
import blue.anika.wolle.ui.search.SearchScreen
import blue.anika.wolle.ui.settings.LibrariesScreen
import blue.anika.wolle.ui.settings.SettingsCategory
import blue.anika.wolle.ui.settings.SettingsCategoryScreen
import blue.anika.wolle.ui.settings.SettingsScreen
import blue.anika.wolle.ui.yarns.YarnDetailScreen
import blue.anika.wolle.ui.yarns.YarnEditorScreen
import blue.anika.wolle.ui.yarns.YarnsListScreen

internal fun shouldNavigateToTopLevelRoot(isAlreadyAtRoot: Boolean): Boolean = !isAlreadyAtRoot

internal fun shouldOpenFreshTopLevelRoot(didPopToRoot: Boolean): Boolean = !didPopToRoot

/**
 * Returns to a bottom-navigation destination's root without adding duplicate roots. If the root
 * is already in the back stack, popping is enough. If a detail route was reached directly (for
 * example from Home) and no root exists yet, clear that nested stack before opening a fresh root.
 */
internal fun navigateToTopLevelRoot(
    navController: NavHostController,
    destination: TopLevelDestination,
) {
    if (
        shouldOpenFreshTopLevelRoot(
            navController.popBackStack(destination.route, inclusive = false)
        )
    ) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * The app's main scaffold: a Material 3 bottom navigation bar switching between the top-level
 * destinations the user has kept visible (`NavBarViewModel`, SNA-16 - defaults to all five in
 * declared order), plus non-top-level detail routes (`ProjectDetail`/`YarnDetail`) and create/edit
 * routes (`ProjectEditor`/`YarnEditor`, SNA-10).
 *
 * @param startDestination [Route.Onboarding] until a server URL + API token are saved, otherwise
 *   [Route.Home] - `MainActivity` picks this reactively from `SettingsRepository.isConfigured` and
 *   recreates this whole composable (a fresh `NavController`) when it flips, rather than this
 *   composable navigating between the two itself.
 * @param pendingDeepLinkRoute a project/yarn link the user tapped (SNA-17,
 *   `MainActivity`/`DeepLinkParser`), navigated to once and then cleared via [onDeepLinkConsumed] -
 *   `null` means there's nothing pending.
 */
@Composable
fun StricknaniNavHost(
    modifier: Modifier = Modifier,
    startDestination: Route = Route.Home,
    navBarViewModel: NavBarViewModel = hiltViewModel(),
    pendingDeepLinkRoute: Route? = null,
    onDeepLinkConsumed: () -> Unit = {},
    mutationFeedback: MutationFeedback,
) {
    val navController = rememberNavController()
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

    MutationFeedbackEffect(mutationFeedback, snackbarHostState)

    LaunchedEffect(pendingDeepLinkRoute) {
        pendingDeepLinkRoute?.let { route ->
            navController.navigate(route)
            onDeepLinkConsumed()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Each destination owns its content scaffold and applies the status-bar inset alongside
        // its own top app bar (or, for Onboarding, its own padding). Applying the outer scaffold's
        // default system-bar insets here would offset that whole destination a second time -
        // exactly the "dead space above the header" bug (see android/TODO.md's note on this fix).
        // NavigationBar still contributes its full height (including the nav-bar inset) to the
        // content padding below - matches syncwich's `SyncwichNavHost` pattern.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (startDestination != Route.Onboarding) {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                val visibleDestinations by
                    navBarViewModel.visibleDestinations.collectAsStateWithLifecycle()

                NavigationBar {
                    visibleDestinations.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any {
                                destination.routeTypes.any { routeType -> it.hasRoute(routeType) }
                            } ?: false
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (
                                    !shouldNavigateToTopLevelRoot(
                                        currentDestination?.hasRoute(destination.route::class) ==
                                            true
                                    )
                                ) {
                                    return@NavigationBarItem
                                }
                                navigateToTopLevelRoot(navController, destination)
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label())
                            },
                            label = { Text(destination.label()) },
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
            composable<Route.Categories> { CategoriesScreen() }
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
            composable<Route.Settings> {
                SettingsScreen(
                    onCategoryClick = { category ->
                        navController.navigate(Route.SettingsCategoryRoute(category.name))
                    }
                )
            }
            composable<Route.SettingsCategoryRoute> { backStackEntry ->
                val route: Route.SettingsCategoryRoute = backStackEntry.toRoute()
                SettingsCategoryScreen(
                    category = SettingsCategory.valueOf(route.categoryName),
                    onBack = { navController.navigateUp() },
                    onShowLibraries = { navController.navigate(Route.Libraries) },
                )
            }
            composable<Route.Libraries> { LibrariesScreen(onBack = { navController.navigateUp() }) }
            composable<Route.ProjectDetail> {
                ProjectDetailScreen(
                    onBack = { navController.navigateUp() },
                    onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                    onEditClick = { id -> navController.navigate(Route.ProjectEditor(id)) },
                    onDeleted = {
                        navController.navigate(Route.Projects) {
                            popUpTo(Route.Projects) { inclusive = true }
                        }
                    },
                )
            }
            composable<Route.YarnDetail> {
                YarnDetailScreen(
                    onBack = { navController.navigateUp() },
                    onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                    onEditClick = { id -> navController.navigate(Route.YarnEditor(id)) },
                    onDeleted = {
                        navController.navigate(Route.Yarns) {
                            popUpTo(Route.Yarns) { inclusive = true }
                        }
                    },
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
