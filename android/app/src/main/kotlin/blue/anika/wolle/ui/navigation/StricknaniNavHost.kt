package blue.anika.wolle.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import blue.anika.wolle.ui.admin.AdminAccountsScreen
import blue.anika.wolle.ui.admin.AdminAuditScreen
import blue.anika.wolle.ui.categories.CategoriesScreen
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.MutationFeedbackEffect
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
 * Returns to a bottom-navigation destination's root without adding duplicate roots. If the root is
 * already in the back stack, popping is enough. If a detail route was reached directly (for example
 * from Home) and no root exists yet, clear that nested stack before opening a fresh root.
 */
private const val SCREEN_TRANSITION_MILLIS = 220

/** The list a deep-linked project or yarn belongs to, or null for any other route. */
internal fun deepLinkParent(route: Route): Route? =
    when (route) {
        is Route.ProjectDetail,
        is Route.ProjectEditor -> Route.Projects
        is Route.YarnDetail,
        is Route.YarnEditor -> Route.Yarns
        else -> null
    }

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
 * @param pendingSetupUri a `stricknani://setup?p=...` URI delivered via the manifest's own
 *   intent-filter (SNA-61, `MainActivity`) - e.g. a generic camera app's QR auto-detection handing
 *   it back to us instead of the in-app scanner. Forwarded once to [OnboardingScreen], which feeds
 *   it through the same [blue.anika.wolle.ui.onboarding.OnboardingViewModel.connectFromScannedText]
 *   path the in-app scanner uses, then cleared via [onSetupUriConsumed]. `null` means nothing
 *   pending - only meaningful pre-onboarding, see [MainActivity]'s call site.
 */
@Composable
fun StricknaniNavHost(
    modifier: Modifier = Modifier,
    startDestination: Route = Route.Home,
    navBarViewModel: NavBarViewModel = hiltViewModel(),
    pendingDeepLinkRoute: Route? = null,
    onDeepLinkConsumed: () -> Unit = {},
    pendingSetupUri: String? = null,
    onSetupUriConsumed: () -> Unit = {},
    mutationFeedback: MutationFeedback,
) {
    val navController = rememberNavController()
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

    MutationFeedbackEffect(mutationFeedback, snackbarHostState)

    LaunchedEffect(pendingDeepLinkRoute) {
        pendingDeepLinkRoute?.let { route ->
            // Put the logical parent list underneath, so Up from a shared project or yarn goes
            // to that list instead of Home - the same rule as the website's header chevron.
            deepLinkParent(route)?.let { parent ->
                navController.navigate(parent) { launchSingleTop = true }
            }
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
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            // Editors are a focused task with their own Save bar at the bottom: hide the tabs so
            // there is one clear way out (Back, which asks before discarding) and no mis-taps.
            val inEditor =
                currentDestination?.hasRoute(Route.ProjectEditor::class) == true ||
                    currentDestination?.hasRoute(Route.YarnEditor::class) == true
            if (startDestination != Route.Onboarding && !inEditor) {
                val visibleDestinations by
                    navBarViewModel.visibleDestinations.collectAsStateWithLifecycle()

                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    visibleDestinations.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any {
                                destination.routeTypes.any { routeType -> it.hasRoute(routeType) }
                            } ?: false
                        val tone = destination.tone()
                        NavigationBarItem(
                            selected = selected,
                            colors =
                                NavigationBarItemDefaults.colors(
                                    selectedIconColor = tone.onContainer,
                                    selectedTextColor = tone.accent,
                                    indicatorColor = tone.container,
                                ),
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
                                // A small happy hop when a tab becomes active.
                                val hop by
                                    animateFloatAsState(
                                        targetValue = if (selected) 1.12f else 1f,
                                        animationSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                        label = "nav-hop",
                                    )
                                Icon(
                                    destination.icon,
                                    contentDescription = destination.label(),
                                    modifier =
                                        Modifier.graphicsLayer {
                                            scaleX = hop
                                            scaleY = hop
                                        },
                                )
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
            // Consuming the bar's padding lets screens use imePadding() without lifting their
            // content twice (keyboard height on top of the tab bar they already sit above).
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            // Opening a screen slides it in a little from the side it comes from and fades; going
            // back reverses that. Short and small so it orients without slowing anyone down; the
            // system "Remove animations" setting scales it to nothing.
            enterTransition = {
                fadeIn(tween(SCREEN_TRANSITION_MILLIS)) +
                    slideInHorizontally(tween(SCREEN_TRANSITION_MILLIS)) { it / 12 }
            },
            exitTransition = { fadeOut(tween(SCREEN_TRANSITION_MILLIS / 2)) },
            popEnterTransition = { fadeIn(tween(SCREEN_TRANSITION_MILLIS)) },
            popExitTransition = {
                fadeOut(tween(SCREEN_TRANSITION_MILLIS / 2)) +
                    slideOutHorizontally(tween(SCREEN_TRANSITION_MILLIS)) { it / 12 }
            },
        ) {
            composable<Route.Onboarding> {
                OnboardingScreen(
                    pendingScannedText = pendingSetupUri,
                    onScannedTextConsumed = onSetupUriConsumed,
                )
            }
            composable<Route.Home> {
                HomeScreen(
                    onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                    onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                    onGaugeClick = { navController.navigate(Route.Gauge) },
                    onOpenSyncIssues = {
                        navController.navigate(
                            Route.SettingsCategoryRoute(SettingsCategory.Sync.name)
                        )
                    },
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
                    onOpenAdminActivity = { navController.navigate(Route.AdminAudit) },
                    onOpenAdminAccounts = { navController.navigate(Route.AdminAccounts) },
                )
            }
            composable<Route.AdminAudit> {
                AdminAuditScreen(onBack = { navController.navigateUp() })
            }
            composable<Route.AdminAccounts> {
                AdminAccountsScreen(onBack = { navController.navigateUp() })
            }
            composable<Route.Libraries> { LibrariesScreen(onBack = { navController.navigateUp() }) }
            composable<Route.ProjectDetail> {
                ProjectDetailScreen(
                    onBack = { navController.navigateUp() },
                    onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                    onEditClick = { id -> navController.navigate(Route.ProjectEditor(id)) },
                    onEditSection = { id, focus ->
                        navController.navigate(Route.ProjectEditor(id, focus))
                    },
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
                    onEditSection = { id, focus ->
                        navController.navigate(Route.YarnEditor(id, focus))
                    },
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

/** Each tab has its own yarn colour, like the web sidebar's tinted icons. */
private data class NavTone(val accent: Color, val container: Color, val onContainer: Color)

@Composable
private fun TopLevelDestination.tone(): NavTone {
    val c = MaterialTheme.colorScheme
    return when (this) {
        TopLevelDestination.HOME,
        TopLevelDestination.SETTINGS -> NavTone(c.primary, c.primaryContainer, c.onPrimaryContainer)
        TopLevelDestination.PROJECTS,
        TopLevelDestination.SEARCH,
        TopLevelDestination.GAUGE -> NavTone(c.tertiary, c.tertiaryContainer, c.onTertiaryContainer)
        TopLevelDestination.YARNS,
        TopLevelDestination.CATEGORIES ->
            NavTone(c.secondary, c.secondaryContainer, c.onSecondaryContainer)
    }
}
