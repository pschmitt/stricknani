package blue.anika.wolle.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.ChangeItemDto
import blue.anika.wolle.data.api.dto.parseChangeValue
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.YarnLoader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAuditScreen(onBack: () -> Unit, viewModel: AdminAuditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).testTag("admin-audit-screen")) {
            AuditFilterBar(
                filter = state.filter,
                onEntity = viewModel::setEntity,
                onAction = viewModel::setAction,
            )
            val phase = state.phase
            when {
                phase is AuditPhase.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        YarnLoader()
                    }
                phase is AuditPhase.Failed ->
                    AdminFailureState(phase.failure, onRetry = viewModel::retry)
                else ->
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (state.events.isEmpty()) {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                EmptyState(
                                    icon = PhosphorIcons.ClockCounterClockwise,
                                    title = stringResource(R.string.admin_activity_empty_title),
                                    subtitle =
                                        stringResource(
                                            if (state.filter.isActive) {
                                                R.string.admin_activity_empty_filtered
                                            } else {
                                                R.string.admin_activity_empty_subtitle
                                            }
                                        ),
                                )
                            }
                        } else {
                            AuditList(
                                state = state,
                                avatarUrl = viewModel::avatarUrl,
                                onOpen = viewModel::openEvent,
                                onLoadMore = viewModel::loadMore,
                            )
                        }
                    }
            }
        }
    }

    if (detail !is AuditDetailState.Closed) {
        AuditDetailSheet(
            state = detail,
            avatarUrl = viewModel::avatarUrl,
            onDismiss = viewModel::closeEvent,
            onRetry = { event -> viewModel.openEvent(event) },
        )
    }
}

@Composable
private fun AuditFilterBar(
    filter: AuditFilter,
    onEntity: (String?) -> Unit,
    onAction: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("admin-audit-filters"),
    ) {
        item {
            FilterChip(
                selected = filter.entity == null,
                onClick = { onEntity(null) },
                label = { Text(stringResource(R.string.admin_filter_all)) },
            )
        }
        items(auditEntityTypes) { type ->
            FilterChip(
                selected = filter.entity == type,
                onClick = { onEntity(if (filter.entity == type) null else type) },
                label = {
                    Text(stringResource(entityTypeLabelRes(type) ?: R.string.admin_entity_other))
                },
            )
        }
        item {
            var open by remember { mutableStateOf(false) }
            Box {
                val actionLabel =
                    filter.action?.let { action ->
                        val presentation = auditActionPresentation(action)
                        presentation.labelRes?.let { stringResource(it) }
                            ?: presentation.fallbackLabel
                    }
                FilterChip(
                    selected = filter.action != null,
                    onClick = { open = true },
                    label = { Text(actionLabel ?: stringResource(R.string.admin_filter_action)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.admin_filter_any_action)) },
                        onClick = {
                            open = false
                            onAction(null)
                        },
                    )
                    knownAuditActionNames.forEach { name ->
                        val presentation = auditActionPresentation(name)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        presentation.labelRes ?: R.string.admin_value_unknown
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    presentation.kind.icon(),
                                    contentDescription = null,
                                    tint = presentation.kind.tone().color(),
                                )
                            },
                            onClick = {
                                open = false
                                onAction(name)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditList(
    state: AuditListState,
    avatarUrl: (String?) -> String?,
    onOpen: (AuditEventDto) -> Unit,
    onLoadMore: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val groups =
        remember(state.events) { groupEventsByDay(state.events, LocalDate.now(zone), zone) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.events.size, state.hasMore) {
        if (nearEnd && state.hasMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("admin-audit-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEachIndexed { index, group ->
            item(key = "day-$index-${group.events.first().id}") {
                DayHeader(
                    group.label,
                    modifier = Modifier.padding(top = if (index == 0) 4.dp else 12.dp),
                )
            }
            items(group.events, key = { it.id }) { event ->
                AuditRow(event, avatarUrl = avatarUrl, onClick = { onOpen(event) })
            }
        }
        if (state.isLoadingMore) {
            item(key = "more-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
        state.loadMoreFailure?.let { failure ->
            item(key = "more-failed") {
                Column(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(failure.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.admin_try_again))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: DayLabel, modifier: Modifier = Modifier) {
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
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp).semantics { heading() },
    )
}

/** The action badge: a tinted rounded square with the action's icon. */
@Composable
internal fun ActionBadge(kind: AuditActionKind, modifier: Modifier = Modifier) {
    val tone = kind.tone().color()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tone.copy(alpha = 0.14f)),
    ) {
        Icon(kind.icon(), contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun entityTitle(event: AuditEventDto): String {
    val typeLabel =
        entityTypeLabelRes(event.entityType)?.let { stringResource(it) } ?: event.entityType
    return event.resourceName()
        ?: stringResource(R.string.admin_entity_fallback, typeLabel, event.entityId)
}

@Composable
private fun actionLabel(action: String): String {
    val presentation = auditActionPresentation(action)
    return presentation.labelRes?.let { stringResource(it) } ?: presentation.fallbackLabel
}

@Composable
private fun formatTime(event: AuditEventDto, style: FormatStyle = FormatStyle.SHORT): String {
    val locale = LocalConfiguration.current.locales[0]
    val instant = auditEventInstant(event) ?: return ""
    return DateTimeFormatter.ofLocalizedTime(style)
        .withLocale(locale)
        .format(instant.atZone(ZoneId.systemDefault()))
}

@Composable
private fun AuditRow(event: AuditEventDto, avatarUrl: (String?) -> String?, onClick: () -> Unit) {
    val kind = auditActionPresentation(event.action).kind
    val tone = kind.tone().color()
    val title = entityTitle(event)
    val action = actionLabel(event.action)
    val typeLabel =
        entityTypeLabelRes(event.entityType)?.let { stringResource(it) } ?: event.entityType
    val time = formatTime(event)
    val actor = event.actorEmail ?: stringResource(R.string.admin_actor_unknown)
    val description = stringResource(R.string.admin_event_description, action, title, actor, time)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(
                    onClickLabel = stringResource(R.string.admin_event_open),
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp).clearAndSetSemantics {},
        ) {
            ActionBadge(kind)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$action · $typeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = tone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AdminAvatar(avatarUrl(event.actorAvatarUrl), event.actorEmail, size = 24.dp)
            }
        }
    }
}

// --- Detail sheet ----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditDetailSheet(
    state: AuditDetailState,
    avatarUrl: (String?) -> String?,
    onDismiss: () -> Unit,
    onRetry: (AuditEventDto) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val event =
            when (state) {
                is AuditDetailState.Loading -> state.event
                is AuditDetailState.Loaded -> state.detail.event
                is AuditDetailState.Failed -> state.event
                AuditDetailState.Closed -> return@ModalBottomSheet
            }
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("admin-audit-detail"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AuditDetailHeader(event, avatarUrl)
            when (state) {
                is AuditDetailState.Loading ->
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        YarnLoader(size = 56.dp)
                    }
                is AuditDetailState.Failed ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(state.failure.messageRes()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.failure != AdminFailure.NotAllowed) {
                            Button(onClick = { onRetry(state.event) }) {
                                Text(stringResource(R.string.admin_try_again))
                            }
                        }
                    }
                is AuditDetailState.Loaded -> {
                    val changes = state.detail.changes
                    if (changes != null) {
                        Text(
                            stringResource(R.string.admin_changes_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (changes.items.isEmpty()) {
                            Text(
                                stringResource(R.string.admin_changes_none),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        changes.items.forEach { item -> ChangeCard(item) }
                    } else {
                        Text(
                            stringResource(unavailableReasonRes(state.detail.unavailableReason)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                AuditDetailState.Closed -> Unit
            }
        }
    }
}

@Composable
private fun AuditDetailHeader(event: AuditEventDto, avatarUrl: (String?) -> String?) {
    val kind = auditActionPresentation(event.action).kind
    val tone = kind.tone().color()
    val typeLabel =
        entityTypeLabelRes(event.entityType)?.let { stringResource(it) } ?: event.entityType
    val locale = LocalConfiguration.current.locales[0]
    val whenText =
        auditEventInstant(event)?.let {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .format(it.atZone(ZoneId.systemDefault()))
        } ?: event.createdAt
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionBadge(kind)
        Column(Modifier.weight(1f)) {
            Text(
                actionLabel(event.action),
                style = MaterialTheme.typography.titleLarge,
                color = tone,
            )
            Text(
                entityTitle(event),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    DetailLine(label = stringResource(R.string.admin_detail_what)) {
        Text(typeLabel, style = MaterialTheme.typography.bodyLarge)
        if (!event.resource.exists) {
            Text(
                stringResource(R.string.admin_detail_gone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    DetailLine(label = stringResource(R.string.admin_detail_who)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdminAvatar(avatarUrl(event.actorAvatarUrl), event.actorEmail, size = 28.dp)
            Text(
                event.actorEmail ?: stringResource(R.string.admin_actor_unknown),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    DetailLine(label = stringResource(R.string.admin_detail_when)) {
        Text(whenText, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailLine(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/**
 * One changed field, before -> after, like the website's DiffPanel: the old value in red with a
 * strikethrough, the new value in green.
 */
@Composable
internal fun ChangeCard(item: ChangeItemDto) {
    val resources = LocalResources.current
    val string: (Int) -> String = { resources.getString(it) }
    val before = parseChangeValue(item.before).displayText(string)
    val after = parseChangeValue(item.after).displayText(string)
    val removed = MaterialTheme.colorScheme.error
    val added = AuditTone.Green.color()
    val fieldLabel =
        changeFieldLabelRes(item.field)?.let(string) ?: string(R.string.admin_field_unknown)
    val beforeLabel = stringResource(R.string.admin_changes_before)
    val afterLabel = stringResource(R.string.admin_changes_after)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                fieldLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            DiffBlock(label = beforeLabel, text = before, color = removed, struck = true)
            DiffBlock(label = afterLabel, text = after, color = added, struck = false)
        }
    }
}

@Composable
private fun DiffBlock(
    label: String,
    text: String,
    color: Color,
    struck: Boolean,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $text" }
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        )
    }
}
