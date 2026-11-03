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
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.SyncIssueBanner
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.common.mascot.MascotCue
import blue.anika.wolle.ui.common.mascot.MascotCueKind
import blue.anika.wolle.ui.common.stitchedBorder
import blue.anika.wolle.ui.signin.SignedOutPanel
import coil3.compose.AsyncImage
import java.util.Calendar
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
    onYarnsClick: () -> Unit,
    onGaugeClick: () -> Unit,
    onOpenSyncIssues: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val favoriteProjects by viewModel.favoriteProjects.collectAsStateWithLifecycle()
    val favoriteYarns by viewModel.favoriteYarns.collectAsStateWithLifecycle()
    val recentProjects by viewModel.recentProjects.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val pendingChangesCount by viewModel.pendingChangesCount.collectAsStateWithLifecycle()
    val failedMutations by viewModel.failedMutations.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
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
            refreshState == RefreshState.Offline || refreshState == RefreshState.Error ->
                cue = MascotCue(MascotCueKind.Sad)
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

    // The compact top bar takes over from the greeting once it has scrolled away.
    val listState = rememberLazyListState()
    val greetingIndex = if (signedOut) 1 else 0
    val handoff by
        remember(listState, greetingIndex, reduceMotion) {
            derivedStateOf {
                val info =
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == greetingIndex }
                val gone =
                    when {
                        info != null -> (-info.offset).coerceAtLeast(0) / info.size.toFloat()
                        listState.firstVisibleItemIndex > greetingIndex -> 1f
                        else -> 0f
                    }
                greetingHandoff(gone, instant = reduceMotion)
            }
        }
    val handoffProgress = remember(listState) { { handoff } }
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
                            onGaugeClick = onGaugeClick,
                            padStatusBar = !signedOut,
                            status = subtitle,
                            busy = refreshing && !signedOut,
                            cue = cue,
                            handoff = handoffProgress,
                        )
                    }
                    availableUpdate?.let { update ->
                        spacedItem {
                            UpdateAvailableCard(
                                update = update,
                                onDownload = { uriHandler.openUri(update.downloadUrl) },
                                onDismiss = viewModel::dismissUpdate,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
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
                            CozyEmptyHome(Modifier.padding(horizontal = 24.dp, vertical = 24.dp))
                        }
                    }
                    current?.let { project ->
                        spacedItem {
                            ContinueKnittingCard(
                                project = project,
                                previewUrl = viewModel.previewUrl(project.previewUrl),
                                onClick = { onProjectClick(project.id) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    spacedItem {
                        StashCard(
                            onClick = onYarnsClick,
                            modifier = Modifier.padding(horizontal = 16.dp),
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
                                        onClick = { onProjectClick(project.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            CollapsedBar(
                progress = handoffProgress,
                busy = refreshing && !signedOut,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
            )
        }
    }
}

/** A list item followed by the 16dp gap the list used to get from `spacedBy`. */
private fun LazyListScope.spacedItem(content: @Composable () -> Unit) {
    item { Column(Modifier.padding(bottom = 16.dp)) { content() } }
}

/**
 * How far the compact top bar has taken over from the greeting, 0 to 1: it fades in as the greeting
 * scrolls out ([gone] is the share of it above the top edge). With [instant] (animations off) it
 * just toggles halfway.
 */
fun greetingHandoff(gone: Float, instant: Boolean): Float {
    val progress = ((gone - HANDOFF_START) / (HANDOFF_END - HANDOFF_START)).coerceIn(0f, 1f)
    return if (instant) (if (progress >= 0.5f) 1f else 0f) else progress
}

private const val HANDOFF_START = 0.25f
private const val HANDOFF_END = 0.7f

/** A friendly hello with the mascot, instead of a title bar. */
@Composable
private fun Greeting(
    onGaugeClick: () -> Unit,
    padStatusBar: Boolean = true,
    status: GreetingStatus = GreetingStatus.Question,
    busy: Boolean = false,
    cue: MascotCue? = null,
    handoff: () -> Float = { 0f },
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
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mascot(
            size = 96.dp,
            bob = true,
            busy = busy,
            cue = cue,
            // It shrinks towards the top bar that takes over from it.
            modifier =
                Modifier.graphicsLayer {
                    val p = handoff()
                    scaleX = 1f - 0.5f * p
                    scaleY = 1f - 0.5f * p
                    alpha = 1f - p
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        )
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
        FilledTonalIconButton(onClick = onGaugeClick) {
            Icon(
                PhosphorIcons.Ruler,
                contentDescription = stringResource(R.string.home_gauge_calculator_description),
            )
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
 * The compact top bar: the small mascot and the app name, with the status bar's inset. It fades and
 * slides in as the greeting scrolls away ([progress]) and is not composed while hidden, so it never
 * blocks touches on the content below. Tapping it scrolls Home back to the top.
 */
@Composable
private fun BoxScope.CollapsedBar(
    progress: () -> Float,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val visible by remember(progress) { derivedStateOf { progress() > 0f } }
    if (!visible) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        modifier =
            Modifier.align(Alignment.TopStart)
                .fillMaxWidth()
                .testTag("home-collapsed-bar")
                .graphicsLayer {
                    val p = progress()
                    alpha = p
                    translationY = -12.dp.toPx() * (1f - p)
                }
                .clickable(
                    onClickLabel = stringResource(R.string.home_scroll_to_top),
                    role = Role.Button,
                    onClick = onClick,
                ),
    ) {
        Row(
            modifier =
                Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mascot(
                size = 44.dp,
                busy = busy,
                exposeSemantics = false,
                modifier =
                    Modifier.graphicsLayer {
                        val p = progress()
                        scaleX = 0.6f + 0.4f * p
                        scaleY = 0.6f + 0.4f * p
                    },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
        }
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

/**
 * The way into the yarn list now that Yarns is not a default tab: a lagoon card with the sheep,
 * right under "Continue knitting".
 */
@Composable
private fun StashCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("home-stash-card"),
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
                Icon(MdiIcons.YarnBall, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_stash_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.home_stash_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun SectionTitle(
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
) {
    Column(modifier = Modifier.width(132.dp)) {
        Box(
            modifier =
                Modifier.size(132.dp)
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
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/** Nothing synced yet: the mascot says hello and explains what to do. */
@Composable
private fun CozyEmptyHome(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mascot(size = 180.dp, bob = true)
        Text(
            stringResource(R.string.home_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.home_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** How long the sync card stays after a successful pull-to-refresh. */
private const val CUE_LIFETIME_MILLIS = 2_000L
