package blue.anika.wolle.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.admin.DayLabel
import blue.anika.wolle.ui.admin.auditActionPresentation
import blue.anika.wolle.ui.admin.color
import blue.anika.wolle.ui.admin.tone
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.UserLabel
import blue.anika.wolle.ui.common.UserLabelSize
import blue.anika.wolle.ui.history.SharingHeadline
import blue.anika.wolle.ui.history.actionLabelText
import blue.anika.wolle.ui.history.sharingEventLine
import coil3.compose.AsyncImage
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Home's "Recent activity": a timeline of the newest edits to the user's projects and yarns, a
 * vertical rail with one coloured dot per entry, grouped under day labels. Online-only, so it
 * degrades to a one-line note without a connection and is not shown at all when the server has no
 * such feed.
 */
@Composable
fun ActivitySection(
    state: ActivityUiState,
    previewUrl: (String?) -> String?,
    onProjectClick: (Int) -> Unit,
    onYarnClick: (Int) -> Unit,
    onTrashClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Home shows the first page only and links to the full page; the page itself pages. */
    onShowMore: (() -> Unit)? = null,
    onSeeAll: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    /** The full page has its own top bar title and also explains a missing feed. */
    showTitle: Boolean = true,
) {
    if (state.phase == ActivityPhase.Hidden && showTitle) return
    Column(modifier) {
        if (showTitle) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    title = stringResource(R.string.activity_title),
                    icon = PhosphorIcons.ClockCounterClockwise,
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                if (
                    onSeeAll != null &&
                        state.phase == ActivityPhase.Ready &&
                        state.items.isNotEmpty()
                ) {
                    TextButton(onClick = onSeeAll, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.activity_see_all))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            when {
                state.phase == ActivityPhase.Unavailable || state.phase == ActivityPhase.Hidden -> {
                    Note(stringResource(R.string.activity_unavailable))
                    if (onRetry != null) {
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.archive_try_again))
                        }
                    }
                }
                state.phase == ActivityPhase.Ready && state.items.isEmpty() ->
                    Note(stringResource(R.string.activity_empty))
                state.phase == ActivityPhase.Ready -> {
                    val zone = remember { ZoneId.systemDefault() }
                    val days =
                        remember(state.items) {
                            groupActivity(state.items, LocalDate.now(zone), zone)
                        }
                    days.forEachIndexed { dayIndex, day ->
                        DayLabelRow(day.label, first = dayIndex == 0)
                        day.rows.forEachIndexed { rowIndex, row ->
                            val last = dayIndex == days.lastIndex && rowIndex == day.rows.lastIndex
                            val first = dayIndex == 0 && rowIndex == 0
                            TimelineEntry(
                                row = row,
                                zone = zone,
                                previewUrl = previewUrl(row.thumbnailUrl),
                                railTop = !first,
                                railBottom = !last,
                                onClick = {
                                    when (val target = row.target) {
                                        is ActivityTarget.Project -> onProjectClick(target.id)
                                        is ActivityTarget.Yarn -> onYarnClick(target.id)
                                        ActivityTarget.Trash -> onTrashClick()
                                        null -> Unit
                                    }
                                },
                            )
                        }
                    }
                    if (state.moreFailed) {
                        Text(
                            stringResource(R.string.activity_more_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = TEXT_INSET, top = 4.dp),
                        )
                    }
                    if (state.hasMore && onShowMore != null) {
                        TextButton(
                            onClick = onShowMore,
                            enabled = !state.loadingMore,
                            modifier =
                                Modifier.padding(start = TEXT_INSET - 12.dp).heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (state.loadingMore) R.string.history_loading
                                    else R.string.activity_show_more
                                )
                            )
                        }
                    }
                }
                else -> SkeletonRows()
            }
        }
    }
}

private val NODE_SIZE = 36.dp
private val NODE_GAP = 12.dp
private val ROW_PAD = 6.dp

/** Where the text column starts: past the rail node and its gap. */
private val TEXT_INSET = NODE_SIZE + NODE_GAP

/**
 * Draws the timeline rail behind a row: a line through the node column, from the row's top (or from
 * the node, for the first row) to its bottom (or to the node, for the last). Day labels draw a
 * plain through-line, so the rail is unbroken from the first node to the last.
 */
private fun Modifier.rail(
    color: Color,
    top: Boolean,
    bottom: Boolean,
    nodeCenterY: Dp? = null,
): Modifier = drawBehind {
    val x = NODE_SIZE.toPx() / 2
    val centre = nodeCenterY?.toPx() ?: size.height / 2
    val from = if (top) 0f else centre
    val to = if (bottom) size.height else centre
    if (to > from) {
        drawLine(
            color,
            Offset(x, from),
            Offset(x, to),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Butt,
        )
    }
}

@Composable
private fun DayLabelRow(label: DayLabel, first: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val text =
        when (label) {
            DayLabel.Today -> stringResource(R.string.admin_day_today)
            DayLabel.Yesterday -> stringResource(R.string.admin_day_yesterday)
            is DayLabel.On ->
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .format(label.date)
            DayLabel.Unknown -> stringResource(R.string.admin_day_unknown)
        }
    val railColor = MaterialTheme.colorScheme.outlineVariant
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (first) Modifier
                    else Modifier.rail(railColor, top = true, bottom = true, nodeCenterY = 0.dp)
                )
                .padding(start = TEXT_INSET, top = if (first) 0.dp else 14.dp, bottom = 2.dp)
                .semantics { heading() },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** Placeholder rows while the first page loads. */
@Composable
private fun SkeletonRows() {
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = ROW_PAD),
                horizontalArrangement = Arrangement.spacedBy(NODE_GAP),
            ) {
                Box(Modifier.size(NODE_SIZE).clip(CircleShape).background(block))
                Column(
                    Modifier.heightIn(min = NODE_SIZE),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier.size(width = 190.dp, height = 14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(block)
                    )
                    Box(
                        Modifier.size(width = 90.dp, height = 10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(block)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineEntry(
    row: ActivityRowModel,
    zone: ZoneId,
    previewUrl: String?,
    railTop: Boolean,
    railBottom: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val muted = colors.onSurfaceVariant
    val tone = auditActionPresentation(row.action, row.system).kind.tone().color()
    val name =
        row.entityName
            ?: stringResource(
                if (row.isYarn) R.string.activity_unnamed_yarn
                else R.string.activity_unnamed_project
            )
    val tappable = !row.deleted
    val alpha = if (row.deleted) 0.6f else 1f
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .rail(
                tone.copy(alpha = 0.35f),
                top = railTop,
                bottom = railBottom,
                nodeCenterY = null,
            )
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (tappable) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(vertical = ROW_PAD)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(NODE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(NODE_SIZE)
                .clip(CircleShape)
                .background(tone.copy(alpha = 0.16f).compositeOver(colors.surface)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                activityIcon(row.icon),
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(
            Modifier.weight(1f).heightIn(min = NODE_SIZE),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textDecoration = if (row.deleted) TextDecoration.LineThrough else null,
                color = colors.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ActivityMeta(row, activityTimeOfDay(row.createdAt, zone), muted)
        }
        if (previewUrl != null) {
            AsyncImage(
                model = previewUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (row.deleted) 0.5f else 1f,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

internal fun activityIcon(kind: ActivityIconKind): ImageVector =
    when (kind) {
        ActivityIconKind.Sparkle -> PhosphorIcons.Sparkle
        ActivityIconKind.Pencil -> PhosphorIcons.PencilSimple
        ActivityIconKind.Camera -> PhosphorIcons.Camera
        ActivityIconKind.Trash -> PhosphorIcons.Trash
        ActivityIconKind.Minus,
        ActivityIconKind.Unshare -> PhosphorIcons.Minus
        ActivityIconKind.Restore -> Icons.AutoMirrored.Filled.Undo
        ActivityIconKind.Timer -> PhosphorIcons.Timer
        ActivityIconKind.Share -> PhosphorIcons.ShareNetwork
        ActivityIconKind.Attachment -> PhosphorIcons.Paperclip
        ActivityIconKind.Other -> PhosphorIcons.NotePencil
    }

/**
 * What happened, who did it and when: "Timer started · by You · 14:32 · 3 changes · notes". The
 * action is worded like the project history; the actor is always named, with their picture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityMeta(row: ActivityRowModel, time: String?, muted: Color) {
    val style = MaterialTheme.typography.labelMedium
    val sharing = sharingEventLine(row.action, row.subject)
    val headline = actionLabelText(row.action, row.system)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (sharing != null) {
            SharingHeadline(
                sharing,
                row.subject,
                style = style.copy(fontWeight = FontWeight.Bold),
                color = muted,
            )
        } else {
            Text(
                headline,
                style = style,
                fontWeight = FontWeight.Bold,
                color =
                    if (row.action == "trashed" && row.target == ActivityTarget.Trash)
                        MaterialTheme.colorScheme.primary
                    else muted,
                textDecoration =
                    if (row.action == "trashed" && row.target == ActivityTarget.Trash)
                        TextDecoration.Underline
                    else null,
            )
        }
        UserLabel(row.actor, size = UserLabelSize.Inline, style = style, color = muted)
        if (time != null) Text(time, style = style, color = muted)
        if (row.count > 1) {
            Text(
                pluralStringResource(R.plurals.activity_changes_count, row.count, row.count),
                style = style,
                color = muted,
            )
        }
        if (row.fieldNounRes.isNotEmpty()) {
            val nouns = row.fieldNounRes.map { stringResource(it) }
            Text(
                (if (row.moreFields > 0)
                        nouns + stringResource(R.string.history_n_more, row.moreFields)
                    else nouns)
                    .joinToString(", "),
                style = style,
                color = muted,
            )
        }
    }
}
