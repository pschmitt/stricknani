package blue.anika.wolle.ui.history

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.ChangeItemDto
import blue.anika.wolle.data.api.dto.HistoryMediaDto
import blue.anika.wolle.ui.admin.ActionBadge
import blue.anika.wolle.ui.admin.AdminAvatar
import blue.anika.wolle.ui.admin.AuditMediaBlock
import blue.anika.wolle.ui.admin.AuditMediaThumb
import blue.anika.wolle.ui.admin.AuditMediaViewModel
import blue.anika.wolle.ui.admin.AuditTone
import blue.anika.wolle.ui.admin.ChangeCard
import blue.anika.wolle.ui.admin.auditActionPresentation
import blue.anika.wolle.ui.admin.auditEventInstant
import blue.anika.wolle.ui.admin.changeFieldLabelRes
import blue.anika.wolle.ui.admin.color
import blue.anika.wolle.ui.admin.displayName
import blue.anika.wolle.ui.admin.tone
import blue.anika.wolle.ui.admin.unavailableReasonRes
import blue.anika.wolle.ui.common.ImageViewerDialog
import blue.anika.wolle.ui.common.ImageViewerImage
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.YarnLoader
import coil3.compose.SubcomposeAsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Recent changes" at the bottom of a project or yarn: a timeline of the newest recorded edits. The
 * caller only adds this item to its list while [HistoryUiState.phase] is not Hidden; the section
 * asks the controller to load when it first composes, so a long detail page never pays for it until
 * the user scrolls here.
 */
@Composable
fun HistorySection(
    state: HistoryUiState,
    controller: HistoryController,
    avatarUrl: (String?) -> String?,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { controller.ensureLoaded() }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { heading() },
            ) {
                Icon(
                    PhosphorIcons.ClockCounterClockwise,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            when (state.phase) {
                HistoryPhase.Idle,
                HistoryPhase.Loading ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), Alignment.Center) {
                        YarnLoader(size = 48.dp)
                    }
                HistoryPhase.Failed ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.history_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = controller::reload,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.admin_try_again))
                        }
                    }
                HistoryPhase.Hidden -> Unit
                HistoryPhase.Ready ->
                    if (state.events.isEmpty()) {
                        HistoryEmpty()
                    } else {
                        HistoryTimeline(state, controller)
                    }
            }
        }
    }

    state.selected?.let { event ->
        HistoryEventSheet(
            event = event,
            detail = state.details[event.id],
            headline = historyHeadline(event, state),
            currentEmail = state.currentEmail,
            avatarUrl = avatarUrl,
            onRetry = { controller.loadDetail(event.id, force = true) },
            onDismiss = controller::close,
        )
    }
}

@Composable
private fun HistoryEmpty() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mascot(size = 72.dp)
        Text(
            stringResource(R.string.history_empty_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.history_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryTimeline(state: HistoryUiState, controller: HistoryController) {
    val shown = visibleEvents(state.events, state.showAll)
    Column {
        shown.forEachIndexed { index, event ->
            HistoryRow(
                event = event,
                headline = historyHeadline(event, state),
                currentEmail = state.currentEmail,
                first = index == 0,
                last = index == shown.lastIndex,
                onOpen = { controller.open(event) },
            )
        }
        if (state.moreFailed) {
            Text(
                stringResource(R.string.history_more_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (canShowAll(state.events, state.hasMore)) {
            Row(
                Modifier.padding(start = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state.showAll) {
                    TextButton(
                        onClick = controller::showAll,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.history_show_all))
                    }
                } else {
                    if (state.hasMore) {
                        TextButton(
                            onClick = controller::loadMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (state.loadingMore) R.string.history_loading
                                    else R.string.history_show_older
                                )
                            )
                        }
                    }
                    TextButton(
                        onClick = controller::showFewer,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.history_show_fewer))
                    }
                }
            }
        }
    }
}

@Composable
private fun actionLabel(action: String): String {
    val presentation = auditActionPresentation(action)
    return presentation.labelRes?.let { stringResource(it) } ?: presentation.fallbackLabel
}

/** The summary of what changed once the details are in, else the plain action name. */
@Composable
private fun historyHeadline(event: AuditEventDto, state: HistoryUiState): String {
    sharingEventLine(event.action, event.subject)?.let { line ->
        val person = line.name ?: stringResource(R.string.history_deleted_account)
        val role =
            stringResource(
                if (line.role == "editor") R.string.sharing_role_editor
                else R.string.sharing_role_viewer
            )
        return stringResource(line.textRes, person, role)
    }
    event.media?.displayName()?.let { name ->
        return "${actionLabel(event.action)}: $name"
    }
    val loaded = state.details[event.id] as? HistoryDetailState.Loaded
    val parts = summaryParts(loaded?.detail?.changes)
    if (parts.isEmpty()) return actionLabel(event.action)
    val andWord = stringResource(R.string.history_list_and)
    val texts = parts.map { part ->
        when (part) {
            is SummaryPart.ChangedFields -> {
                val nouns = part.nounRes.map { stringResource(it) }
                val shown =
                    if (nouns.size > 3) {
                        nouns.take(2) + stringResource(R.string.history_n_more, nouns.size - 2)
                    } else nouns
                stringResource(R.string.history_changed_fields, joinNatural(shown, andWord))
            }
            is SummaryPart.CountChange -> countText(part)
        }
    }
    return texts.joinToString(" · ")
}

@Composable
private fun countText(part: SummaryPart.CountChange): String {
    val n = kotlin.math.abs(part.delta)
    val adding = part.delta > 0
    val res =
        when (part.kind) {
            HistoryCountKind.Photo ->
                if (adding) R.plurals.history_added_photos else R.plurals.history_removed_photos
            HistoryCountKind.Step ->
                if (adding) R.plurals.history_added_steps else R.plurals.history_removed_steps
            HistoryCountKind.Attachment ->
                if (adding) R.plurals.history_added_attachments
                else R.plurals.history_removed_attachments
        }
    return pluralStringResource(res, n, n)
}

@Composable
private fun relativeTime(event: AuditEventDto): String {
    val instant = auditEventInstant(event) ?: return event.createdAt
    return DateUtils.getRelativeTimeSpanString(
            instant.toEpochMilli(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        .toString()
}

@Composable
private fun fullDate(event: AuditEventDto): String {
    val locale = LocalConfiguration.current.locales[0]
    return auditEventInstant(event)?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(it.atZone(ZoneId.systemDefault()))
    } ?: event.createdAt
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    event: AuditEventDto,
    headline: String,
    currentEmail: String?,
    first: Boolean,
    last: Boolean,
    onOpen: () -> Unit,
) {
    val kind = auditActionPresentation(event.action).kind
    val tone = kind.tone().color()
    val railColor = tone.copy(alpha = 0.35f)
    val other = otherActor(event.actorEmail, currentEmail)
    val action = actionLabel(event.action)
    val time = relativeTime(event)
    val date = fullDate(event)
    val context = LocalContext.current
    val byText = other?.let { stringResource(R.string.history_by, it) }
    val description = listOfNotNull(headline, action, date, byText).joinToString(", ")
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val rowModifier =
        Modifier.fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = true,
                role = Role.Button,
                onClickLabel = stringResource(R.string.history_open_changes),
                onClick = onOpen,
                onLongClick = { Toast.makeText(context, date, Toast.LENGTH_SHORT).show() },
                onLongClickLabel = stringResource(R.string.history_show_date),
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
    Row(rowModifier) {
        Box(
            Modifier.width(28.dp).fillMaxHeight().drawBehind {
                val x = 10.dp.toPx()
                val dotY = 22.dp.toPx()
                if (!first || !last) {
                    drawLine(
                        color = railColor,
                        start = Offset(x, if (first) dotY else 0f),
                        end = Offset(x, if (last) dotY else size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                drawCircle(color = surface, radius = 7.dp.toPx(), center = Offset(x, dotY))
                drawCircle(color = tone, radius = 5.dp.toPx(), center = Offset(x, dotY))
            }
        )
        Row(
            Modifier.weight(1f).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = tone, fontWeight = FontWeight.Bold)) {
                            append(action)
                        }
                        append(" · $time")
                        if (byText != null) append(" · $byText")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AuditMediaThumb(event.media, Modifier.padding(horizontal = 8.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryEventSheet(
    event: AuditEventDto,
    detail: HistoryDetailState?,
    headline: String,
    currentEmail: String?,
    avatarUrl: (String?) -> String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val kind = auditActionPresentation(event.action).kind
    val tone = kind.tone().color()
    val other = otherActor(event.actorEmail, currentEmail)
    val detailMedia = (detail as? HistoryDetailState.Loaded)?.detail?.event?.mediaThumbs.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionBadge(kind)
                Column(Modifier.weight(1f)) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        actionLabel(event.action),
                        style = MaterialTheme.typography.labelLarge,
                        color = tone,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (other != null) {
                    AdminAvatar(avatarUrl(event.actorAvatarUrl), event.actorEmail, size = 24.dp)
                    Text(other, style = MaterialTheme.typography.bodyMedium)
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    fullDate(event),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val body = historyDetailBody(event, detail)) {
                is HistoryDetailBody.Media -> AuditMediaBlock(body.media)
                HistoryDetailBody.NoDetails ->
                    Text(
                        stringResource(R.string.admin_no_details),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("history-no-details"),
                    )
                HistoryDetailBody.Loading ->
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        YarnLoader(size = 56.dp)
                    }
                HistoryDetailBody.Failed ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.history_detail_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.admin_try_again))
                        }
                    }
                is HistoryDetailBody.Changes ->
                    if (body.items.isEmpty()) {
                        Text(
                            stringResource(R.string.admin_changes_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val thumbs = event.mediaThumbs.ifEmpty { detailMedia }
                        body.items.forEach { item ->
                            val rows = mediaChangeRows(item, thumbs)
                            if (rows != null) MediaChangeCard(item, rows, thumbs)
                            else ChangeCard(item)
                        }
                    }
                is HistoryDetailBody.Unavailable ->
                    Text(
                        stringResource(unavailableReasonRes(body.reason)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

private val ThumbSize = 52.dp

/**
 * A picture-order, cover or primary-photo change with thumbnails instead of file labels: a Before
 * and an After row, the pictures that were added, removed or moved framed in their color.
 */
@Composable
fun MediaChangeCard(
    item: ChangeItemDto,
    rows: MediaChangeRows,
    media: Map<String, HistoryMediaDto>,
) {
    val viewModel: AuditMediaViewModel = hiltViewModel()
    val fieldLabel =
        changeFieldLabelRes(item.field)?.let { stringResource(it) }
            ?: stringResource(R.string.admin_field_unknown)
    val viewable =
        mediaViewerLabels(rows, media).mapNotNull { label ->
            viewModel.resolve(media[label]?.url)?.let { url ->
                label to ImageViewerImage(url = url, title = label, sourceLabel = fieldLabel)
            }
        }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val open: (String) -> Unit = { label ->
        viewable.indexOfFirst { it.first == label }.takeIf { it >= 0 }?.let { viewerIndex = it }
    }
    viewerIndex?.let { index ->
        ImageViewerDialog(viewable.map { it.second }, index, onDismiss = { viewerIndex = null })
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("history-media-change"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                fieldLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            MediaThumbRow(stringResource(R.string.admin_changes_before), rows.before, media, open)
            MediaThumbRow(stringResource(R.string.admin_changes_after), rows.after, media, open)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaThumbRow(
    label: String,
    thumbs: List<MediaThumb>,
    media: Map<String, HistoryMediaDto>,
    onOpen: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (thumbs.isEmpty()) {
            Text(
                stringResource(R.string.admin_value_not_provided),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                thumbs.forEach { thumb -> MediaThumbTile(thumb, media[thumb.label], onOpen) }
            }
        }
    }
}

@Composable
private fun markColor(mark: MediaMark): Color =
    when (mark) {
        MediaMark.Kept -> MaterialTheme.colorScheme.outlineVariant
        MediaMark.Added -> AuditTone.Green.color()
        MediaMark.Removed -> MaterialTheme.colorScheme.error
        MediaMark.Moved -> MaterialTheme.colorScheme.tertiary
    }

@Composable
private fun MediaThumbTile(
    thumb: MediaThumb,
    entry: HistoryMediaDto?,
    onOpen: (String) -> Unit,
) {
    val viewModel: AuditMediaViewModel = hiltViewModel()
    val color = markColor(thumb.mark)
    val shape = RoundedCornerShape(10.dp)
    val markText =
        when (thumb.mark) {
            MediaMark.Kept -> null
            MediaMark.Added -> stringResource(R.string.history_media_added)
            MediaMark.Removed -> stringResource(R.string.history_media_removed)
            MediaMark.Moved -> stringResource(R.string.history_media_moved)
        }
    val description = listOfNotNull(thumb.label, markText).joinToString(", ")
    val badge =
        when (thumb.mark) {
            MediaMark.Kept -> null
            MediaMark.Added -> "+"
            MediaMark.Removed -> "\u2212"
            MediaMark.Moved -> "\u2195"
        }
    val picture = entry?.let { viewModel.resolve(it.thumbnailUrl ?: it.url) }
    Box(
        Modifier.size(ThumbSize)
            .clip(shape)
            .border(
                if (thumb.mark == MediaMark.Kept) 1.dp else 2.dp,
                color,
                shape,
            )
            .then(
                if (entry?.url != null)
                    Modifier.clickable(role = Role.Button) { onOpen(thumb.label) }
                else Modifier
            )
            .semantics { contentDescription = description }
    ) {
        if (picture != null) {
            SubcomposeAsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                },
                error = { MediaThumbLabel(thumb.label) },
            )
        } else {
            MediaThumbLabel(thumb.label)
        }
        if (badge != null) {
            Text(
                badge,
                color = MaterialTheme.colorScheme.surface,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .background(color, RoundedCornerShape(bottomStart = 8.dp))
                        .padding(horizontal = 5.dp)
                        .clearAndSetSemantics {},
            )
        }
    }
}

/** The file label as a text tile, for a picture the server no longer has. */
@Composable
private fun MediaThumbLabel(label: String) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
        Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
