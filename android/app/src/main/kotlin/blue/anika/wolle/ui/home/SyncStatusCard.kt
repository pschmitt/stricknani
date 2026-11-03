package blue.anika.wolle.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.common.burstParticles
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persistent Home feedback for the background sync/write-queue (user-requested, 2026-08-18, "like
 * in syncwich and nyetbox") - it never gates or replaces Room content, just reports on it. Simpler
 * than syncwich's full `SyncStatus` state machine (no live syncing/stale-threshold tracking) since
 * that needs deeper WorkManager state observation this pass didn't scope in; covers the states this
 * app's data actually exposes today: refreshing, queued-but-unsynced local edits (SNA-8), a replay
 * failure, or a plain last-synced timestamp.
 *
 * SNA-60: whenever there's actually something to act on (a sync failure, or edits still queued to
 * replay), the whole card is tappable - previously it was a dead end (`uiautomator dump` confirmed
 * `clickable="false"` on both the card and its text), leaving a permanent, unresolvable-looking
 * warning on the primary screen. Tapping a sync-failure state opens the same sync-issues detail
 * screen [SyncIssueBanner]'s "View details" does (per-change retry/discard); tapping a
 * pending-but-not-yet-failed state triggers an immediate replay+sync instead of waiting for the
 * next periodic run. The idle "synced"/"syncing" states have nothing to act on and stay
 * non-interactive, same as before.
 */
@Composable
fun HomeSyncStatusCard(
    isRefreshing: Boolean,
    lastSyncedMillis: Long?,
    pendingChangesCount: Int,
    failedChangesCount: Int,
    onOpenSyncIssues: () -> Unit,
    onRetrySync: () -> Unit,
    modifier: Modifier = Modifier,
    // Set when the last pull could not check for news; the card is then the only place saying so.
    refreshProblem: SyncCardProblem? = null,
    onRetryRefresh: () -> Unit = {},
    leaving: Boolean = false,
    // Raw PullToRefreshState.distanceFraction, read lazily (layout/draw phase only).
    pullFraction: () -> Float = { 0f },
    // True while the card exists only because the user is dragging: it is revealed by the pull.
    peeking: Boolean = false,
) {
    // The failed count includes conflicts, which the pending count leaves out; the
    // failure text must use it so the card agrees with SyncIssueBanner below it.
    val hasSyncFailures = failedChangesCount > 0
    val hasProblem = refreshProblem != null && !isRefreshing
    val isActionable = !isRefreshing && (hasSyncFailures || pendingChangesCount > 0 || hasProblem)
    val actionLabel =
        if (hasSyncFailures) stringResource(R.string.sync_issue_view_details)
        else stringResource(R.string.sync_issue_retry)
    val still = animationsDisabled()
    val mood = syncBadgeMood(isRefreshing, pendingChangesCount, hasSyncFailures, refreshProblem)
    val density = LocalDensity.current
    // Enter: drop in from slightly above with a squash-and-stretch spring.
    val enter = remember { Animatable(if (still || peeking) 1f else 0f) }
    val exit = remember { Animatable(0f) }
    // The badge pops in a beat after the card.
    val badgePop = remember { Animatable(if (still || peeking) 1f else 0f) }
    val hop = remember { Animatable(1f) }
    val wiggle = remember { Animatable(0f) }
    val bob = remember { Animatable(0f) }
    val sparkles = remember { Animatable(0f) }
    val particles = remember { burstParticles(count = 6, nearRadius = 20f, farRadius = 28f) }
    var previousMood by remember { mutableStateOf<SyncBadgeMood?>(null) }

    var wasPeeking by remember { mutableStateOf(peeking) }
    // Released past the threshold: settle from wherever the drag left the card with a spring.
    LaunchedEffect(peeking) {
        if (wasPeeking && !peeking && !still) {
            enter.snapTo(syncPullReveal(pullFraction()))
            enter.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        wasPeeking = peeking
    }
    LaunchedEffect(still) {
        if (still || peeking) return@LaunchedEffect
        launch {
            enter.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        delay(80)
        badgePop.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }
    LaunchedEffect(leaving, still) {
        if (leaving && !still)
            exit.animateTo(1f, tween(SYNC_CARD_EXIT_MILLIS, easing = FastOutSlowInEasing))
        else if (!leaving) exit.snapTo(0f)
    }
    LaunchedEffect(mood, still) {
        val cue = syncBadgeCue(previousMood, mood)
        previousMood = mood
        if (still) return@LaunchedEffect
        coroutineScope {
            when (cue) {
                SyncBadgeCue.Hop -> {
                    launch {
                        sparkles.snapTo(0f)
                        sparkles.animateTo(1f, tween(550, easing = LinearEasing))
                    }
                    launch {
                        for (angle in SYNC_HOP_WIGGLE_ANGLES) wiggle.animateTo(angle, tween(90))
                    }
                    hop.animateTo(1.25f, tween(130, easing = FastOutSlowInEasing))
                    hop.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                }
                SyncBadgeCue.Shake ->
                    for (angle in SYNC_SHAKE_ANGLES) wiggle.animateTo(angle, tween(70))
                SyncBadgeCue.None -> Unit
            }
        }
    }
    // While busy the badge floats up and down; it settles back when the refresh ends.
    LaunchedEffect(mood == SyncBadgeMood.Busy, still) {
        if (mood != SyncBadgeMood.Busy || still) {
            bob.animateTo(0f, tween(150))
            return@LaunchedEffect
        }
        while (true) {
            bob.animateTo(-1f, tween(520, easing = FastOutSlowInEasing))
            bob.animateTo(0.4f, tween(520, easing = FastOutSlowInEasing))
        }
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // While dragging, the card's height grows with the pull.
                    val placeable = measurable.measure(constraints)
                    val shown = if (peeking) syncPullReveal(pullFraction()).coerceIn(0f, 1f) else 1f
                    layout(placeable.width, (placeable.height * shown).roundToInt()) {
                        placeable.place(0, 0)
                    }
                }
                .then(if (peeking) Modifier.clipToBounds() else Modifier)
                .graphicsLayer {
                    val appear = if (peeking) syncPullReveal(pullFraction()) else enter.value
                    val nudge = if (peeking || isRefreshing) 0f else syncPullNudge(pullFraction())
                    val gone = exit.value
                    val scale = (0.85f + 0.15f * appear) * (1f - 0.1f * gone)
                    scaleX = scale
                    // Squash and stretch: a touch taller on the way down, a touch flatter on
                    // landing.
                    scaleY = scale * (1f + 0.04f * (1f - appear).coerceAtLeast(0f) + nudge)
                    translationY = with(density) { (-16.dp * (1f - appear) - 12.dp * gone).toPx() }
                    alpha = (appear.coerceIn(0f, 1f)) * (1f - gone)
                }
                .testTag("home-sync-status-card")
                .let {
                    if (isActionable) {
                        it.clickable(
                            onClickLabel = actionLabel,
                            role = Role.Button,
                            onClick =
                                if (hasSyncFailures) onOpenSyncIssues
                                else if (hasProblem) onRetryRefresh else onRetrySync,
                        )
                    } else {
                        it
                    }
                }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A calm, larger status badge: teal when all is well (raspberry read like an error),
            // marigold while changes wait, red only for real failures.
            val (badgeTint, badgeOn) =
                when {
                    hasSyncFailures || hasProblem ->
                        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
                    pendingChangesCount > 0 && !isRefreshing ->
                        MaterialTheme.colorScheme.tertiaryContainer to
                            MaterialTheme.colorScheme.onTertiaryContainer
                    else ->
                        MaterialTheme.colorScheme.secondaryContainer to
                            MaterialTheme.colorScheme.onSecondaryContainer
                }
            Box(
                modifier =
                    Modifier.size(44.dp)
                        .drawBehind {
                            val progress = sparkles.value
                            if (progress <= 0f || progress >= 1f) return@drawBehind
                            particles.forEach { particle ->
                                val local =
                                    ((progress - particle.delayFraction) /
                                            (1f - particle.delayFraction))
                                        .coerceIn(0f, 1f)
                                if (local <= 0f) return@forEach
                                val radius = particle.size.dp.toPx() / 2 * (0.4f + 0.6f * local)
                                drawCircle(
                                    color = badgeOn.copy(alpha = 1f - local),
                                    radius = radius,
                                    center =
                                        Offset(
                                            center.x + particle.dx.dp.toPx() * local,
                                            center.y + particle.dy.dp.toPx() * local,
                                        ),
                                )
                            }
                        }
                        .graphicsLayer {
                            val reveal =
                                if (peeking) syncPullReveal(pullFraction()).coerceIn(0f, 1f) else 1f
                            val pop = badgePop.value * hop.value * reveal * reveal
                            scaleX = pop
                            scaleY = pop
                            rotationZ = wiggle.value
                            translationY = bob.value * 3.dp.toPx()
                        }
                        .clip(CircleShape)
                        .background(badgeTint),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isRefreshing ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = badgeOn,
                        )
                    hasSyncFailures || hasProblem ->
                        Icon(
                            PhosphorIcons.CloudSlash,
                            contentDescription = null,
                            tint = badgeOn,
                            modifier = Modifier.size(28.dp),
                        )
                    pendingChangesCount > 0 ->
                        Icon(
                            PhosphorIcons.CloudArrowUp,
                            contentDescription = null,
                            tint = badgeOn,
                            modifier = Modifier.size(28.dp),
                        )
                    else ->
                        Icon(
                            PhosphorIcons.CloudCheck,
                            contentDescription = null,
                            tint = badgeOn,
                            modifier = Modifier.size(28.dp),
                        )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (hasProblem && !hasSyncFailures)
                        stringResource(
                            if (refreshProblem == SyncCardProblem.Offline)
                                R.string.sync_status_offline
                            else R.string.sync_status_refresh_failed
                        )
                    else syncStatusHeadline(isRefreshing, pendingChangesCount, hasSyncFailures),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (hasProblem && !hasSyncFailures)
                        stringResource(R.string.sync_status_showing_saved)
                    else
                        syncStatusDetails(
                            isRefreshing,
                            lastSyncedMillis,
                            pendingChangesCount,
                            failedChangesCount,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasProblem && !hasSyncFailures) {
                TextButton(
                    onClick = onRetryRefresh,
                    modifier = Modifier.testTag("home-sync-retry"),
                ) {
                    Text(stringResource(R.string.sync_issue_retry), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun syncStatusHeadline(
    isRefreshing: Boolean,
    pendingChangesCount: Int,
    hasSyncFailures: Boolean,
): String =
    when {
        isRefreshing -> stringResource(R.string.sync_status_syncing)
        hasSyncFailures -> stringResource(R.string.sync_status_issue)
        pendingChangesCount > 0 -> stringResource(R.string.sync_status_changes_pending)
        else -> stringResource(R.string.sync_status_synced)
    }

@Composable
internal fun syncStatusDetails(
    isRefreshing: Boolean,
    lastSyncedMillis: Long?,
    pendingChangesCount: Int,
    failedChangesCount: Int,
    nowMillis: Long = System.currentTimeMillis(),
): String =
    when {
        isRefreshing -> stringResource(R.string.sync_status_pulling_latest)
        failedChangesCount > 0 ->
            pluralStringResource(
                R.plurals.sync_issue_count,
                failedChangesCount,
                failedChangesCount,
            )
        pendingChangesCount > 0 ->
            pluralStringResource(
                R.plurals.sync_status_changes_waiting,
                pendingChangesCount,
                pendingChangesCount,
            )
        else -> formatRelativeSyncTime(lastSyncedMillis, nowMillis)
    }

@Composable
internal fun formatRelativeSyncTime(lastSyncAt: Long?, nowMillis: Long): String {
    if (lastSyncAt == null) return stringResource(R.string.sync_status_not_synced_yet)
    val deltaMillis = (nowMillis - lastSyncAt).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMillis)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMillis)
    return when {
        minutes < 1 -> stringResource(R.string.sync_status_last_synced_just_now)
        minutes < 60 -> stringResource(R.string.sync_status_last_synced_minutes, minutes)
        hours < 24 -> stringResource(R.string.sync_status_last_synced_hours, hours)
        days < 7 -> stringResource(R.string.sync_status_last_synced_days, days)
        else ->
            stringResource(
                R.string.sync_status_last_synced_date,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(lastSyncAt)),
            )
    }
}
