package blue.anika.wolle.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.update.canInstallInApp
import blue.anika.wolle.ui.common.LocalOwnerContext
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.OwnerBadge
import blue.anika.wolle.ui.common.OwnerBadgeData
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.SyncIssueBanner
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.common.isProblem
import blue.anika.wolle.ui.common.mascot.MascotCue
import blue.anika.wolle.ui.common.mascot.MascotCueKind
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.stitchedBorder
import blue.anika.wolle.ui.create.ImportOfferViewModel
import blue.anika.wolle.ui.create.ImportOffers
import blue.anika.wolle.ui.create.moreCreateSubtitleRes
import blue.anika.wolle.ui.imports.WaitingForReview
import blue.anika.wolle.ui.signin.SignedOutPanel
import blue.anika.wolle.ui.update.UpdateInstallEffects
import blue.anika.wolle.ui.update.UpdateInstallViewModel
import coil3.compose.AsyncImage
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    signedOut: Boolean,
    signInWorking: Boolean,
    onSignIn: () -> Unit,
    onProjectClick: (Int) -> Unit,
    onYarnClick: (Int) -> Unit,
    onCreateClick: () -> Unit,
    onOpenSyncIssues: () -> Unit,
    onSeeAllActivity: () -> Unit = {},
    /** Opens the editor on an import draft that waits for review. */
    onOpenDraft: (ImportJobDto) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    importOfferViewModel: ImportOfferViewModel = hiltViewModel(),
) {
    val currentUserId = LocalOwnerContext.current.currentUserId
    val importOffers by importOfferViewModel.offers.collectAsStateWithLifecycle()
    val favoriteProjects by viewModel.favoriteProjects.collectAsStateWithLifecycle()
    val favoriteYarns by viewModel.favoriteYarns.collectAsStateWithLifecycle()
    val recentProjects by viewModel.recentProjects.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val pendingChangesCount by viewModel.pendingChangesCount.collectAsStateWithLifecycle()
    val failedMutations by viewModel.failedMutations.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val installViewModel: UpdateInstallViewModel = hiltViewModel()
    val installState by installViewModel.state.collectAsStateWithLifecycle()
    UpdateInstallEffects(installViewModel, installState)
    LaunchedEffect(signedOut) { viewModel.onSignedOutChanged(signedOut) }
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }

    RefreshFeedbackEffect(
        refreshState,
        snackbarHostState,
        viewModel::dismissRefreshFeedback,
        problemsShownInline = true,
    )
    // The project touched last gets the big card; the row below shows the others.
    val current = recentProjects.firstOrNull()
    val otherRecent = recentProjects.drop(1)
    val isEmpty = favoriteProjects.isEmpty() && favoriteYarns.isEmpty() && current == null

    // The greeting and the mascot report on a refresh (see HomeSyncFeedback); only a failure adds
    // a card. The feedback effect consumes finished states at once, so the outcome and the problem
    // are kept here.
    val reduceMotion = animationsDisabled()
    val refreshing = refreshState is RefreshState.Refreshing
    var outcome by remember { mutableStateOf<GreetingStatus?>(null) }
    var outcomeId by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<SyncProblem?>(null) }
    var cue by remember { mutableStateOf<MascotCue?>(null) }
    LaunchedEffect(refreshState, signedOut) {
        problem = problemAfter(problem, refreshState, signedOut)
        if (signedOut) return@LaunchedEffect
        val status = greetingStatus(refreshState, signedOut)
        when {
            refreshing -> outcome = null
            status.isOutcome() -> {
                outcome = status
                outcomeId++
                cue = MascotCue(MascotCueKind.Happy)
            }
            refreshState.isProblem() -> cue = MascotCue(MascotCueKind.Sad)
        }
    }
    // A cue plays once; clearing it keeps a greeting that scrolls back in from replaying it.
    LaunchedEffect(cue) {
        if (cue == null) return@LaunchedEffect
        delay(CUE_LIFETIME_MILLIS)
        cue = null
    }
    LaunchedEffect(outcomeId) {
        if (outcome == null) return@LaunchedEffect
        delay(GREETING_OUTCOME_MILLIS)
        outcome = null
    }
    val subtitle =
        when {
            signedOut -> GreetingStatus.Question
            refreshing -> GreetingStatus.Checking
            else -> outcome ?: GreetingStatus.Question
        }

    // The pull indicator is the stock one: the greeting and the mascot carry the status.
    val pullState = rememberPullToRefreshState()

    // The hero mascot flies into the compact top bar as the list scrolls (see HeroFlight).
    val listState = rememberLazyListState()
    val greetingIndex = if (signedOut) 1 else 0
    val density = LocalDensity.current
    val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val flight =
        remember(listState, greetingIndex, signedOut, statusTopPx, density, reduceMotion) {
            with(density) {
                HeroFlight(
                    listState = listState,
                    greetingIndex = greetingIndex,
                    restLeftPx = HERO_START_PADDING.toPx(),
                    restTopPx = (if (signedOut) 0f else statusTopPx) + GREETING_TOP_PADDING.toPx(),
                    heroPx = HERO_SIZE.toPx(),
                    slotLeftPx = BAR_PADDING.toPx(),
                    slotTopPx = statusTopPx + ((BAR_HEIGHT - BAR_MASCOT_SIZE) / 2).toPx(),
                    slotPx = BAR_MASCOT_SIZE.toPx(),
                    instant = reduceMotion,
                )
            }
        }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
        // The greeting below replaces the title bar, so the list draws under the status bar
        // itself and pads for it. A compact bar fades in once the greeting has scrolled away.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                state = pullState,
                onRefresh = {
                    viewModel.refresh()
                },
                modifier =
                    Modifier.fillMaxSize()
                        .padding(innerPadding)
                        .testTag("e2e-home-refresh")
                        .semantics {
                            // Lets tests wait for a refresh to settle without the sync card being
                            // shown.
                            stateDescription = if (isRefreshing) "refreshing" else "idle"
                        },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Each item brings its own 16dp bottom gap (see spacedItem) so the sync card's
                    // gap
                    // can grow with the pull; the last item's gap makes up part of the old 24dp.
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    if (signedOut) {
                        spacedItem {
                            SignedOutPanel(
                                pendingChangesCount = pendingChangesCount,
                                working = signInWorking,
                                onSignIn = onSignIn,
                            )
                        }
                    }
                    spacedItem {
                        Greeting(
                            padStatusBar = !signedOut,
                            status = subtitle,
                        )
                    }
                    availableUpdate?.let { update ->
                        // Swipe it away or tap Later: the card leaves with an animation and only
                        // then is the dismissal remembered (which drops the item).
                        item(key = "update-card") {
                            DismissibleCard(
                                onDismissed = viewModel::dismissUpdate,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) { requestDismiss ->
                                UpdateAvailableCard(
                                    update = update,
                                    install = installState,
                                    onDownload = {
                                        // Installs in-app when the release has this build's APK
                                        // and checksums; otherwise the browser gets the release
                                        // page.
                                        if (update.canInstallInApp()) installViewModel.start(update)
                                        else uriHandler.openUri(update.downloadUrl)
                                    },
                                    onTryAgain = {
                                        installViewModel.dismissFailure()
                                        installViewModel.start(update)
                                    },
                                    onOpenBrowser = {
                                        installViewModel.dismissFailure()
                                        uriHandler.openUri(update.downloadUrl)
                                    },
                                    onDismiss = requestDismiss,
                                )
                            }
                        }
                    }
                    problem?.let { shown ->
                        spacedItem {
                            SyncProblemCard(
                                problem = shown,
                                onRetry = { viewModel.refresh() },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    spacedItem {
                        SyncIssueBanner(
                            issues = failedMutations,
                            onRetry = viewModel::retryFailedMutations,
                            onOpenDetails = onOpenSyncIssues,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    if (isEmpty) {
                        spacedItem {
                            CozyEmptyHome(
                                localMode = viewModel.isLocalMode,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                            )
                        }
                    }
                    current?.let { project ->
                        spacedItem {
                            ContinueKnittingCard(
                                project = project,
                                previewUrl = viewModel.previewUrl(project.previewUrl),
                                owner = project.ownerBadge(currentUserId),
                                onClick = { onProjectClick(project.id) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    if (!viewModel.isLocalMode && !signedOut) {
                        spacedItem {
                            WaitingForReview(
                                target = null,
                                onOpen = onOpenDraft,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    spacedItem {
                        CreateCard(
                            onClick = onCreateClick,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            localMode = viewModel.isLocalMode,
                            offers = importOffers,
                        )
                    }
                    if (favoriteProjects.isNotEmpty()) {
                        spacedItem {
                            HomeShelf(
                                title = stringResource(R.string.home_favorite_projects_title),
                                icon = PhosphorIcons.Heart,
                                accent = MaterialTheme.colorScheme.primary,
                            ) {
                                itemsIndexed(favoriteProjects, key = { _, it -> "fp-${it.id}" }) {
                                    index,
                                    project ->
                                    ShelfTile(
                                        title = project.name,
                                        previewUrl = viewModel.previewUrl(project.previewUrl),
                                        fallbackIcon = PhosphorIcons.Hoodie,
                                        tint = tileTint(index),
                                        owner = project.ownerBadge(currentUserId),
                                        onClick = { onProjectClick(project.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (favoriteYarns.isNotEmpty()) {
                        spacedItem {
                            HomeShelf(
                                title = stringResource(R.string.home_favorite_yarns_title),
                                icon = MdiIcons.YarnBall,
                                accent = MaterialTheme.colorScheme.secondary,
                            ) {
                                itemsIndexed(favoriteYarns, key = { _, it -> "fy-${it.id}" }) {
                                    index,
                                    yarn ->
                                    ShelfTile(
                                        title = yarn.name,
                                        previewUrl = viewModel.previewUrl(yarn.previewUrl),
                                        fallbackIcon = MdiIcons.YarnBall,
                                        tint = tileTint(index + 1),
                                        owner = yarn.ownerBadge(currentUserId),
                                        onClick = { onYarnClick(yarn.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (otherRecent.isNotEmpty()) {
                        spacedItem {
                            HomeShelf(
                                title = stringResource(R.string.home_recent_projects_title),
                                icon = PhosphorIcons.ClockCounterClockwise,
                                accent = MaterialTheme.colorScheme.tertiary,
                            ) {
                                itemsIndexed(otherRecent, key = { _, it -> "rp-${it.id}" }) {
                                    index,
                                    project ->
                                    ShelfTile(
                                        title = project.name,
                                        previewUrl = viewModel.previewUrl(project.previewUrl),
                                        fallbackIcon = PhosphorIcons.Hoodie,
                                        tint = tileTint(index + 2),
                                        owner = project.ownerBadge(currentUserId),
                                        onClick = { onProjectClick(project.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (activity.phase != ActivityPhase.Hidden) {
                        spacedItem {
                            ActivitySection(
                                state = activity,
                                previewUrl = viewModel::previewUrl,
                                onProjectClick = onProjectClick,
                                onYarnClick = onYarnClick,
                                onSeeAll = onSeeAllActivity,
                            )
                        }
                    }
                }
            }
            CollapsedBar(
                progress = flight::progress,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
            )
            // The one and only Winky Pom: it rides along with the greeting at the top and shrinks
            // into the bar's logo slot as the list scrolls, so it stays tappable in both places.
            Mascot(
                size = HERO_SIZE,
                bob = true,
                busy = refreshing && !signedOut,
                cue = cue,
                modifier =
                    Modifier.align(Alignment.TopStart).graphicsLayer {
                        val placement = flight.placement()
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = placement.x
                        translationY = placement.y
                        scaleX = placement.scale
                        scaleY = placement.scale
                    },
            )
        }
    }
}

/** A list item followed by the 16dp gap the list used to get from `spacedBy`. */
private fun LazyListScope.spacedItem(content: @Composable () -> Unit) {
    item { Column(Modifier.padding(bottom = 16.dp)) { content() } }
}

private val HERO_SIZE = 96.dp
private val HERO_START_PADDING = 8.dp
private val GREETING_TOP_PADDING = 8.dp
private val BAR_HEIGHT = 56.dp
private val BAR_PADDING = 16.dp
private val BAR_MASCOT_SIZE = 44.dp
private val BAR_MASCOT_GAP = 8.dp

/** A friendly hello next to the mascot's place, instead of a title bar. */
@Composable
private fun Greeting(
    padStatusBar: Boolean = true,
    status: GreetingStatus = GreetingStatus.Question,
) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    // One pick per screen entry: it survives recomposition, theme switches and rotation.
    val pick = rememberSaveable { Random.nextInt(GREETING_POOL_SIZE) }
    val greeting = stringResource(greetingRes(hour, pick))
    // A small pop when the greeting appears; skipped when animations are off.
    val reduceMotion = animationsDisabled()
    var popped by rememberSaveable { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { popped = true }
    val pop by
        animateFloatAsState(
            targetValue = if (popped) 1f else 0.92f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "greetingPop",
        )
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (padStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    else Modifier
                )
                .padding(start = HERO_START_PADDING, end = 16.dp, top = GREETING_TOP_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The mascot itself is drawn by HomeScreen's overlay, which flies it into the top bar;
        // this only keeps its place in the row.
        Spacer(Modifier.size(HERO_SIZE).align(Alignment.Top))
        Column(Modifier.weight(1f)) {
            Text(
                greeting,
                style = MaterialTheme.typography.headlineMedium,
                modifier =
                    Modifier.graphicsLayer {
                            scaleX = pop
                            scaleY = pop
                            alpha = ((pop - 0.92f) / 0.08f).coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .semantics { heading() },
            )
            // Polite live region: TalkBack announces "Checking for news", then the result.
            Crossfade(
                targetState = status,
                animationSpec = tween(if (reduceMotion) 0 else 250),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                label = "greetingSubtitle",
            ) { shown ->
                Text(
                    greetingStatusText(shown),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun greetingStatusText(status: GreetingStatus): String =
    when (status) {
        GreetingStatus.Question -> stringResource(R.string.home_greeting_question)
        GreetingStatus.Checking -> stringResource(R.string.home_greeting_checking)
        GreetingStatus.CaughtUp -> stringResource(R.string.home_greeting_caught_up)
        is GreetingStatus.Updated ->
            if (status.count != null)
                pluralStringResource(R.plurals.home_greeting_updates, status.count, status.count)
            else stringResource(R.string.home_greeting_updated)
    }

/**
 * The compact top bar: a logo slot that opens as the hero mascot flies in ([progress], 0 to 1) and
 * pushes the app name right in step, with the status bar's inset. It is transparent while the hero
 * is around and fills in once the mascot has arrived, like a collapsing top bar. The mascot itself
 * is HomeScreen's overlay, so the slot is only empty space. Not composed at 0, so it never blocks
 * touches on the content below; once filled, tapping it scrolls Home back to the top.
 */
@Composable
private fun BoxScope.CollapsedBar(progress: () -> Float, onClick: () -> Unit) {
    val visible by remember(progress) { derivedStateOf { progress() > 0f } }
    if (!visible) return
    val tappable by remember(progress) { derivedStateOf { progress() >= 0.5f } }
    val container = MaterialTheme.colorScheme.surface
    val slotPx = with(LocalDensity.current) { (BAR_MASCOT_SIZE + BAR_MASCOT_GAP).toPx() }
    Row(
        modifier =
            Modifier.align(Alignment.TopStart)
                .fillMaxWidth()
                .testTag("home-collapsed-bar")
                .graphicsLayer {
                    shadowElevation = 3.dp.toPx() * morphBarOpacity(progress())
                    shape = RectangleShape
                }
                .drawBehind { drawRect(container, alpha = morphBarOpacity(progress())) }
                .clickable(
                    enabled = tappable,
                    onClickLabel = stringResource(R.string.home_scroll_to_top),
                    role = Role.Button,
                    onClick = onClick,
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(BAR_HEIGHT)
                .padding(horizontal = BAR_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The logo slot: 0 wide at the top of Home, full width once the mascot sits in it.
        Spacer(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(morphSlotWidth(progress(), slotPx).roundToInt(), placeable.height) {}
            }
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier.graphicsLayer { alpha = morphBarOpacity(progress()) },
        )
    }
}

/**
 * The one big card: the project worked on last, like a patch sewn onto the page - stitched edge,
 * its photo pinned slightly askew - with a soft press-down.
 */
@Composable
private fun ContinueKnittingCard(
    project: ProjectEntity,
    previewUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    owner: OwnerBadgeData? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val still = animationsDisabled()
    val scale by
        animateFloatAsState(
            targetValue = if (pressed && !still) 0.97f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "continue-press",
        )
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        SectionTitle(
            title = stringResource(R.string.home_continue_title),
            icon = Icons.Filled.Schedule,
            accent = colors.primary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            modifier =
                Modifier.fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                    .stitchedBorder(colors.onPrimaryContainer.copy(alpha = 0.35f))
                    .testTag("home-continue-card"),
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier =
                        Modifier.size(112.dp)
                            .graphicsLayer { rotationZ = -4f }
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewUrl != null) {
                        AsyncImage(
                            model = previewUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Mascot(size = 96.dp, reactive = false)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        project.category?.let { Pill(it) }
                        Pill(relativeDay(project.updatedAt))
                    }
                    owner?.let { OwnerBadge(it, size = 24.dp) }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "Today", "Yesterday", "3 days ago" in the device language. */
@Composable
private fun relativeDay(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.DAY_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        .toString()

/** The way into the Create screen: a lagoon card right under "Continue knitting". */
@Composable
private fun CreateCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    localMode: Boolean = false,
    offers: ImportOffers = ImportOffers(),
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("home-create-card"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.Sparkle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.more_create_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(moreCreateSubtitleRes(offers, localMode)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
internal fun SectionTitle(
    title: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun HomeShelf(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: LazyListScope.() -> Unit,
) {
    Column {
        SectionTitle(
            title = title,
            icon = icon,
            accent = accent,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            content()
        }
    }
}

/**
 * Cycles the tiles through the three yarn colours, so a shelf without photos still looks lively.
 */
@Composable
private fun tileTint(index: Int): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (index % 3) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
}

@Composable
private fun ShelfTile(
    title: String,
    previewUrl: String?,
    fallbackIcon: ImageVector,
    tint: Pair<Color, Color>,
    onClick: () -> Unit,
    owner: OwnerBadgeData? = null,
) {
    Column(modifier = Modifier.width(132.dp)) {
        Box(modifier = Modifier.size(132.dp)) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .background(tint.first)
                        .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (previewUrl != null) {
                    AsyncImage(
                        model = previewUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        fallbackIcon,
                        contentDescription = null,
                        tint = tint.second,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            owner?.let {
                OwnerBadge(
                    it,
                    size = 24.dp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/**
 * Nothing synced yet: a short hello and what to do. No second mascot: the greeting's mascot is
 * right above.
 */
@Composable
private fun CozyEmptyHome(localMode: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.home_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(
                if (localMode) R.string.home_welcome_subtitle_local
                else R.string.home_welcome_subtitle
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** How long the sync card stays after a successful pull-to-refresh. */
private const val CUE_LIFETIME_MILLIS = 2_000L
