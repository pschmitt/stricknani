package blue.anika.wolle.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import blue.anika.wolle.ui.admin.AdminAccountsScreen
import blue.anika.wolle.ui.admin.AdminAuditScreen
import blue.anika.wolle.ui.categories.CategoriesScreen
import blue.anika.wolle.ui.common.AccountAvatar
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.MutationFeedbackEffect
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.create.CreateScreen
import blue.anika.wolle.ui.gauge.GaugeCalculatorScreen
import blue.anika.wolle.ui.home.HomeScreen
import blue.anika.wolle.ui.oidc.SessionExpiredViewModel
import blue.anika.wolle.ui.onboarding.OnboardingScreen
import blue.anika.wolle.ui.projects.ProjectDetailScreen
import blue.anika.wolle.ui.projects.ProjectEditorScreen
import blue.anika.wolle.ui.projects.ProjectsListScreen
import blue.anika.wolle.ui.search.SearchScreen
import blue.anika.wolle.ui.settings.AccountAvatarViewModel
import blue.anika.wolle.ui.settings.LibrariesScreen
import blue.anika.wolle.ui.settings.SettingsCategory
import blue.anika.wolle.ui.settings.SettingsCategoryScreen
import blue.anika.wolle.ui.settings.SettingsScreen
import blue.anika.wolle.ui.signin.SignInAgainScreen
import blue.anika.wolle.ui.signin.SignedOutBanner
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
    accountAvatarViewModel: AccountAvatarViewModel = hiltViewModel(),
    pendingDeepLinkRoute: Route? = null,
    onDeepLinkConsumed: () -> Unit = {},
    pendingSetupUri: String? = null,
    onSetupUriConsumed: () -> Unit = {},
    mutationFeedback: MutationFeedback,
) {
    val navController = rememberNavController()
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }
    val motion = androidx.compose.runtime.remember { ScreenMotion() }
    val reduceMotion = animationsDisabled()

    MutationFeedbackEffect(mutationFeedback, snackbarHostState)

    // The server no longer accepts this phone's token: Home shows a big panel, every other screen
    // a slim banner. An OIDC session signs in again through its provider, others on their own
    // screen.
    val sessionViewModel: SessionExpiredViewModel = hiltViewModel()
    val signedOut by sessionViewModel.ended.collectAsStateWithLifecycle()
    val sessionIsOidc by sessionViewModel.isOidc.collectAsStateWithLifecycle()
    val signInWorking by sessionViewModel.working.collectAsStateWithLifecycle()
    val signInAgain: () -> Unit = {
        if (sessionIsOidc) sessionViewModel.signInAgain()
        else navController.navigate(Route.SignInAgain) { launchSingleTop = true }
    }

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
        snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
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
                val accountProfile by accountAvatarViewModel.profile.collectAsStateWithLifecycle()

                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    visibleDestinations.forEach { destination ->
                        val selected =
                            isTabSelected(currentDestination, destination, visibleDestinations)
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
                                motion.markTabSwitch()
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
                                val hopModifier = Modifier.graphicsLayer {
                                    scaleX = hop
                                    scaleY = hop
                                }
                                val avatarUrl = accountProfile.avatarUrl
                                if (
                                    destination == TopLevelDestination.SETTINGS && avatarUrl != null
                                ) {
                                    // The More tab shows the account avatar; it is decorative, the
                                    // tab is still named "More" by its label.
                                    AccountAvatar(
                                        avatarUrl,
                                        accountProfile.email,
                                        size = 24.dp,
                                        modifier = hopModifier,
                                    )
                                } else {
                                    Icon(
                                        destination.icon,
                                        contentDescription = destination.label(),
                                        modifier = hopModifier,
                                    )
                                }
                            },
                            label = { Text(destination.label()) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val bannerEntry by navController.currentBackStackEntryAsState()
        val onHome = bannerEntry?.destination?.hasRoute(Route.Home::class) == true
        val onSignInScreen = bannerEntry?.destination?.hasRoute(Route.SignInAgain::class) == true
        val showSignedOutBanner =
            signedOut && startDestination != Route.Onboarding && !onHome && !onSignInScreen
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (showSignedOutBanner) {
                SignedOutBanner(working = signInWorking, onSignIn = signInAgain)
            }
            NavHost(
                navController = navController,
                startDestination = startDestination,
                // Consuming the bar's padding lets screens use imePadding() without lifting their
                // content twice (keyboard height on top of the tab bar they already sit above).
                // With
                // the banner showing, it has already taken the status bar's space.
                modifier =
                    Modifier.weight(1f)
                        .then(
                            if (showSignedOutBanner)
                                Modifier.consumeWindowInsets(WindowInsets.statusBars)
                            else Modifier
                        )
                        .consumeWindowInsets(innerPadding),
                // Drilling down stacks a card on top of the screen below, back unstacks it (and the
                // predictive-back gesture peels it); editors rise as a sheet; tab switches
                // cross-fade.
                // See ScreenMotion.kt. Animations switched off in system settings means no motion.
                enterTransition = { motion.enter(this, isPop = false, reduceMotion) },
                exitTransition = { motion.exit(this, isPop = false, reduceMotion) },
                popEnterTransition = { motion.enter(this, isPop = true, reduceMotion) },
                popExitTransition = { motion.exit(this, isPop = true, reduceMotion) },
            ) {
                screen<Route.Onboarding>(motion) {
                    OnboardingScreen(
                        pendingScannedText = pendingSetupUri,
                        onScannedTextConsumed = onSetupUriConsumed,
                    )
                }
                screen<Route.SignInAgain>(motion) {
                    SignInAgainScreen(onDone = { navController.navigateUp() })
                }
                screen<Route.Home>(motion) {
                    HomeScreen(
                        signedOut = signedOut,
                        signInWorking = signInWorking,
                        onSignIn = signInAgain,
                        onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                        onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                        onYarnsClick = { navController.navigate(Route.Yarns) },
                        onGaugeClick = { navController.navigate(Route.Gauge) },
                        onOpenSyncIssues = {
                            navController.navigate(
                                Route.SettingsCategoryRoute(SettingsCategory.Sync.name)
                            )
                        },
                    )
                }
                screen<Route.Projects>(motion) {
                    ProjectsListScreen(
                        onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                        onAddProjectClick = { navController.navigate(Route.ProjectEditor()) },
                    )
                }
                screen<Route.Categories>(motion) { CategoriesScreen() }
                screen<Route.Yarns>(motion) {
                    val tabs by navBarViewModel.visibleDestinations.collectAsStateWithLifecycle()
                    YarnsListScreen(
                        onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                        onAddYarnClick = { navController.navigate(Route.YarnEditor()) },
                        // Without a Yarns tab the list is opened from Home, so offer a way back.
                        onBack =
                            if (TopLevelDestination.YARNS in tabs) null
                            else ({ navController.popBackStack() }),
                    )
                }
                screen<Route.Search>(motion) {
                    SearchScreen(
                        onProjectClick = { id -> navController.navigate(Route.ProjectDetail(id)) },
                        onYarnClick = { id -> navController.navigate(Route.YarnDetail(id)) },
                    )
                }
                screen<Route.Settings>(motion) {
                    SettingsScreen(
                        onCategoryClick = { category ->
                            navController.navigate(Route.SettingsCategoryRoute(category.name))
                        },
                        onCreateClick = { navController.navigate(Route.Create) },
                        onGaugeClick = { navController.navigate(Route.Gauge) },
                    )
                }
                screen<Route.Create>(motion) {
                    CreateScreen(
                        onBack = { navController.navigateUp() },
                        onNewProject = { navController.navigate(Route.ProjectEditor()) },
                        onNewYarn = { navController.navigate(Route.YarnEditor()) },
                    )
                }
                screen<Route.SettingsCategoryRoute>(motion) { backStackEntry ->
                    val route: Route.SettingsCategoryRoute = backStackEntry.toRoute()
                    SettingsCategoryScreen(
                        category = SettingsCategory.valueOf(route.categoryName),
                        onBack = { navController.navigateUp() },
                        onShowLibraries = { navController.navigate(Route.Libraries) },
                        onOpenAdminActivity = { navController.navigate(Route.AdminAudit) },
                        onOpenAdminAccounts = { navController.navigate(Route.AdminAccounts) },
                        onOpenCategory = {
                            navController.navigate(Route.SettingsCategoryRoute(it.name))
                        },
                    )
                }
                screen<Route.AdminAudit>(motion) {
                    AdminAuditScreen(
                        onBack = { navController.navigateUp() },
                        onOpenProject = { id -> navController.navigate(Route.ProjectDetail(id)) },
                        onOpenYarn = { id -> navController.navigate(Route.YarnDetail(id)) },
                    )
                }
                screen<Route.AdminAccounts>(motion) {
                    AdminAccountsScreen(onBack = { navController.navigateUp() })
                }
                screen<Route.Libraries>(motion) {
                    LibrariesScreen(onBack = { navController.navigateUp() })
                }
                screen<Route.ProjectDetail>(motion) {
                    ProjectDetailScreen(
                        onBack = { navController.navigateUp() },
                        onHome = {
                            navController.navigate(Route.Home) {
                                popUpTo(Route.Home) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
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
                screen<Route.YarnDetail>(motion) {
                    YarnDetailScreen(
                        onBack = { navController.navigateUp() },
                        onHome = {
                            navController.navigate(Route.Home) {
                                popUpTo(Route.Home) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
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
                screen<Route.ProjectEditor>(motion) {
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
                screen<Route.YarnEditor>(motion) {
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
                screen<Route.Gauge>(motion) {
                    GaugeCalculatorScreen(onBack = { navController.navigateUp() })
                }
            }
        }
    }
}

/** `composable<T>` whose content also gets the stack/unstack card visuals ([MotionLayer]). */
private inline fun <reified T : Route> NavGraphBuilder.screen(
    motion: ScreenMotion,
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry -> MotionLayer(motion, entry.id) { content(entry) } }
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

/**
 * Whether [destination] is the current tab. More also owns the pages opened from it (Gauge), but
 * only when no other visible tab claims the current page, so two tabs never look selected.
 */
private fun isTabSelected(
    current: androidx.navigation.NavDestination?,
    destination: TopLevelDestination,
    visible: List<TopLevelDestination>,
): Boolean {
    fun owns(candidate: TopLevelDestination) =
        current?.hierarchy?.any { node ->
            candidate.routeTypes.any { routeType -> node.hasRoute(routeType) }
        } == true
    if (destination != TopLevelDestination.SETTINGS) return owns(destination)
    return owns(destination) ||
        (visible.none { it != destination && owns(it) } &&
            current?.hasRoute(Route.Gauge::class) == true)
}
