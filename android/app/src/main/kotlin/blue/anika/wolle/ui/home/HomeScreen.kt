package blue.anika.wolle.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.SyncIssueBanner
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.common.stitchedBorder
import coil3.compose.AsyncImage
import java.util.Calendar
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
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
    val lastSyncedMillis by viewModel.lastSyncedMillis.collectAsStateWithLifecycle()
    val pendingChangesCount by viewModel.pendingChangesCount.collectAsStateWithLifecycle()
    val failedMutations by viewModel.failedMutations.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    // The project touched last gets the big card; the row below shows the others.
    val current = recentProjects.firstOrNull()
    val otherRecent = recentProjects.drop(1)
    val isEmpty = favoriteProjects.isEmpty() && favoriteYarns.isEmpty() && current == null
    // The sync card is noise when everything is fine: show it only when something needs
    // attention (failures, changes waiting, never synced yet) or once the user pulled to
    // refresh here and so asked for the status.
    var pulledToRefresh by rememberSaveable { mutableStateOf(false) }
    // After a pull, the card says "All caught up" for a moment and then gets out of the way -
    // but only when nothing went wrong and nothing is waiting; problems stay visible.
    // The card plays a short "poof" before it leaves the list, so the item is only removed after.
    var leavingSyncCard by remember { mutableStateOf(false) }
    val reduceMotion = animationsDisabled()
    LaunchedEffect(pulledToRefresh, isRefreshing, failedMutations.isEmpty(), pendingChangesCount) {
        leavingSyncCard = false
        if (
            pulledToRefresh &&
                !isRefreshing &&
                failedMutations.isEmpty() &&
                pendingChangesCount == 0
        ) {
            delay(SYNC_CARD_LINGER_MILLIS)
            if (!reduceMotion) {
                leavingSyncCard = true
                delay(SYNC_CARD_EXIT_MILLIS.toLong())
            }
            pulledToRefresh = false
            leavingSyncCard = false
        }
    }
    val showSyncStatus =
        pulledToRefresh ||
            failedMutations.isNotEmpty() ||
            pendingChangesCount > 0 ||
            lastSyncedMillis == null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The greeting below replaces the title bar, so the list draws under the status bar
        // itself and pads for it.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                pulledToRefresh = true
                viewModel.refresh()
            },
            modifier =
                Modifier.fillMaxSize().padding(innerPadding).testTag("e2e-home-refresh").semantics {
                    // Lets tests wait for a refresh to settle without the sync card being shown.
                    stateDescription = if (isRefreshing) "refreshing" else "idle"
                },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Greeting(onGaugeClick = onGaugeClick) }
                availableUpdate?.let { update ->
                    item {
                        UpdateAvailableCard(
                            update = update,
                            onDownload = { uriHandler.openUri(update.downloadUrl) },
                            onDismiss = viewModel::dismissUpdate,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                if (showSyncStatus)
                    item(key = "sync-status") {
                        HomeSyncStatusCard(
                            isRefreshing = isRefreshing,
                            lastSyncedMillis = lastSyncedMillis,
                            pendingChangesCount = pendingChangesCount,
                            failedChangesCount = failedMutations.size,
                            onOpenSyncIssues = onOpenSyncIssues,
                            onRetrySync = viewModel::retryFailedMutations,
                            leaving = leavingSyncCard,
                            // Fades in/out while the cards below slide into place (and nothing when
                            // animations
                            // are off - animateItem follows the system animator scale).
                            modifier =
                                Modifier.animateItem(
                                        fadeInSpec = tween(220),
                                        fadeOutSpec = tween(160),
                                        placementSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                                visibilityThreshold = IntOffset.VisibilityThreshold,
                                            ),
                                    )
                                    .padding(horizontal = 16.dp),
                        )
                    }
                item {
                    SyncIssueBanner(
                        issues = failedMutations,
                        onRetry = viewModel::retryFailedMutations,
                        onOpenDetails = onOpenSyncIssues,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (isEmpty) {
                    item { CozyEmptyHome(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) }
                }
                current?.let { project ->
                    item {
                        ContinueKnittingCard(
                            project = project,
                            previewUrl = viewModel.previewUrl(project.previewUrl),
                            onClick = { onProjectClick(project.id) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                item {
                    StashCard(
                        onClick = onYarnsClick,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (favoriteProjects.isNotEmpty()) {
                    item {
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
                    item {
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
                    item {
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
    }
}

/** A friendly hello with the mascot, instead of a title bar. */
@Composable
private fun Greeting(onGaugeClick: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting =
        stringResource(
            when (hour) {
                in 5..11 -> R.string.home_greeting_morning
                in 12..17 -> R.string.home_greeting_day
                in 18..22 -> R.string.home_greeting_evening
                else -> R.string.home_greeting_night
            }
        )
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mascot(size = 96.dp, bob = true)
        Column(Modifier.weight(1f)) {
            Text(
                greeting,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.home_greeting_question),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(onClick = onGaugeClick) {
            Icon(
                PhosphorIcons.Ruler,
                contentDescription = stringResource(R.string.home_gauge_calculator_description),
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
                        Mascot(size = 96.dp)
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
private const val SYNC_CARD_LINGER_MILLIS = 10_000L
