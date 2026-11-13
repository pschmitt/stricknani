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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditClientDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditFilterUserDto
import blue.anika.wolle.data.api.dto.ChangeItemDto
import blue.anika.wolle.data.api.dto.parseChangeValue
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.ui.common.DiffSegment
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.UserKind
import blue.anika.wolle.ui.common.UserLabel
import blue.anika.wolle.ui.common.UserLabelSize
import blue.anika.wolle.ui.common.UserSentence
import blue.anika.wolle.ui.common.WordDiff
import blue.anika.wolle.ui.common.YarnLoader
import blue.anika.wolle.ui.history.HistoryActor
import blue.anika.wolle.ui.history.MediaChangeCard
import blue.anika.wolle.ui.history.mediaChangeRows
import blue.anika.wolle.ui.home.activityIcon
import blue.anika.wolle.ui.home.activityIconKind
import blue.anika.wolle.ui.trash.daysLeft
import blue.anika.wolle.ui.trash.daysLeftText
import coil3.compose.AsyncImage
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAuditScreen(
    onBack: () -> Unit,
    onOpenProject: (Int) -> Unit = {},
    onOpenYarn: (Int) -> Unit = {},
    onOpenTrash: () -> Unit = {},
    viewModel: AdminAuditViewModel = hiltViewModel(),
) {
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
                user = state.user,
                onEntity = viewModel::setEntity,
                onAction = viewModel::setAction,
                onClearUser = viewModel::clearUser,
            )
            val phase = state.phase
            when {
                phase is AuditPhase.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        YarnLoader()
                    }
                phase is AuditPhase.Failed ->
                    AdminFailureState(phase.failure, onRetry = viewModel::retry, onBack = onBack)
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
                                filterUser = state.user,
                                avatarUrl = viewModel::avatarUrl,
                                onOpen = viewModel::openEvent,
                                onOpenItem = { target ->
                                    when (target) {
                                        is AuditTarget.Project -> onOpenProject(target.id.toInt())
                                        is AuditTarget.Yarn -> onOpenYarn(target.id.toInt())
                                        is AuditTarget.InTrash -> onOpenTrash()
                                        else -> Unit
                                    }
                                },
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
            onOpenTrash = {
                viewModel.closeEvent()
                onOpenTrash()
            },
            onRestore = viewModel::restore,
        )
    }
}

@Composable
private fun AuditFilterBar(
    filter: AuditFilter,
    user: AuditFilterUserDto?,
    onEntity: (String?) -> Unit,
    onAction: (String?) -> Unit,
    onClearUser: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("admin-audit-filters"),
    ) {
        if (filter.userId != null) {
            item {
                // Selected like the web chip; tapping removes the user filter.
                FilterChip(
                    selected = true,
                    onClick = onClearUser,
                    label = {
                        if (user != null) {
                            UserSentence(
                                R.string.admin_filter_user,
                                user.ref(),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        } else {
                            Text(stringResource(R.string.admin_filter_user, "#${filter.userId}"))
                        }
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.admin_filter_user_remove),
                        )
                    },
                    modifier = Modifier.testTag("admin-audit-user-chip"),
                )
            }
        }
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
    filterUser: AuditFilterUserDto?,
    avatarUrl: (String?) -> String?,
    onOpen: (AuditEventDto) -> Unit,
    onOpenItem: (AuditTarget) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        groups.forEachIndexed { index, group ->
            item(key = "day-$index-${group.events.first().id}") {
                DayHeader(
                    group.label,
                    modifier = Modifier.padding(top = if (index == 0) 4.dp else 12.dp),
                )
            }
            itemsIndexed(group.events, key = { _, event -> event.id }) { rowIndex, event ->
                AuditRow(
                    event,
                    first = rowIndex == 0,
                    last = rowIndex == group.events.lastIndex,
                    filterUser = filterUser,
                    avatarUrl = avatarUrl,
                    onOpenDetails = { onOpen(event) },
                    onOpenItem = onOpenItem,
                )
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
private fun actionLabel(action: String, system: Boolean = false): String {
    val presentation = auditActionPresentation(action, system)
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
private fun AuditRow(
    event: AuditEventDto,
    first: Boolean,
    last: Boolean,
    filterUser: AuditFilterUserDto?,
    avatarUrl: (String?) -> String?,
    onOpenDetails: () -> Unit,
    onOpenItem: (AuditTarget) -> Unit,
) {
    val kind = auditActionPresentation(event.action).kind
    val tone = kind.tone().color()
    val title = entityTitle(event)
    val action = actionLabel(event.action, event.system)
    val time = formatTime(event)
    val actor =
        event.actorEmail ?: event.server?.actorEmail ?: stringResource(R.string.admin_actor_unknown)
    val actorRef = event.auditActorRef()
    val target = event.target()
    // An item in the trash has no page (it would answer 404): a tap opens its details, whose link
    // leads to the Trash screen.
    val navigable = target is AuditTarget.Project || target is AuditTarget.Yarn
    val description = stringResource(R.string.admin_event_description, action, title, actor, time)
    val detailKind = event.detailKind()
    Surface(
        color = Color.Transparent,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .drawBehind {
                    val x = 32.dp.toPx()
                    val centre = size.height / 2
                    if (!first || !last) {
                        drawLine(
                            color = tone.copy(alpha = 0.35f),
                            start = Offset(x, if (first) centre else 0f),
                            end = Offset(x, if (last) centre else size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
                .testTag("admin-audit-row"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(
                        tone.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    activityIcon(activityIconKind(event.action)),
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(21.dp),
                )
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier.weight(1f)
                                .clickable(
                                    onClickLabel =
                                        if (navigable)
                                            stringResource(R.string.admin_open_item, title)
                                        else stringResource(R.string.admin_event_open),
                                    onClick = {
                                        if (navigable) onOpenItem(target) else onOpenDetails()
                                    },
                                )
                                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
                                .semantics { contentDescription = description }
                                .testTag("admin-audit-row-main"),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                action,
                                style = MaterialTheme.typography.labelMedium,
                                color = tone,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            event.media?.let { media ->
                                Text(
                                    auditMediaNote(media),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            event.server?.let { server ->
                                Text(
                                    stringResource(
                                        R.string.admin_server_safety_copy,
                                        server.preRestore,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("admin-audit-row-server"),
                                )
                                Text(
                                    stringResource(
                                        if (server.media == "add_missing")
                                            R.string.admin_server_media_added
                                        else R.string.admin_server_media_kept
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (target is AuditTarget.Deleted) {
                                Text(
                                    stringResource(R.string.admin_item_deleted),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("admin-audit-row-deleted"),
                                )
                            }
                            event.inTrashState()?.let { inTrash ->
                                Text(
                                    inTrashText(inTrash),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("admin-audit-row-in-trash"),
                                )
                            }
                            event.lifecycleOwnerRef()?.let { owner ->
                                UserSentence(
                                    R.string.admin_owner_line,
                                    owner,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HistoryActor(event, actorRef, UserLabelSize.Inline, by = false)
                            val role = filterUser?.let { event.userRole(it) }
                            if (role != null && filterUser != null) {
                                val person = filterUser.ref()
                                UserSentence(
                                    when (role) {
                                        AuditUserRole.Actor -> R.string.admin_role_actor
                                        AuditUserRole.Impersonator ->
                                            R.string.admin_role_impersonator
                                        AuditUserRole.SubjectOfAccount ->
                                            R.string.admin_role_subject_account
                                        AuditUserRole.Subject -> R.string.admin_role_subject
                                    },
                                    *(if (role == AuditUserRole.Impersonator)
                                        arrayOf(person, actorRef)
                                    else arrayOf(person)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("admin-audit-user-role"),
                                )
                            }
                            Text(
                                time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        avatarUrl(event.resource.previewUrl)?.let { preview ->
                            AsyncImage(
                                model = preview,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier.padding(horizontal = 8.dp)
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                            )
                        }
                        AuditMediaThumb(event.media, Modifier.padding(horizontal = 8.dp))
                    }
                    IconButton(
                        onClick = onOpenDetails,
                        modifier = Modifier.testTag("admin-audit-details"),
                    ) {
                        Icon(
                            when (detailKind) {
                                AuditDetailKind.Media -> Icons.Filled.Image
                                AuditDetailKind.Changes -> PhosphorIcons.ClockCounterClockwise
                                AuditDetailKind.None -> Icons.Filled.Info
                            },
                            contentDescription =
                                stringResource(
                                    when (detailKind) {
                                        AuditDetailKind.Media ->
                                            if (event.media?.isImage == true)
                                                R.string.admin_media_show_picture
                                            else R.string.admin_media_open_file
                                        AuditDetailKind.Changes -> R.string.admin_event_open
                                        AuditDetailKind.None -> R.string.admin_no_details
                                    }
                                ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
    onOpenTrash: () -> Unit,
    onRestore: (AuditEventDto) -> Unit,
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
            AuditTrashActions(event, onOpenTrash, onRestore)
            when (state) {
                is AuditDetailState.Loading ->
                    if (state.event.media != null) {
                        AuditMediaBlock(state.event.media)
                    } else {
                        Box(
                            Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            YarnLoader(size = 56.dp)
                        }
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
                    val media = state.detail.event.media
                    if (media != null) {
                        AuditMediaBlock(media)
                    } else if (changes != null) {
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
                        val thumbs = state.detail.event.mediaThumbs
                        changes.items.forEach { item ->
                            val rows = mediaChangeRows(item, thumbs)
                            if (rows != null) MediaChangeCard(item, rows, thumbs)
                            else ChangeCard(item)
                        }
                    } else if (state.detail.unavailableReason == "not_recorded") {
                        Text(
                            stringResource(R.string.admin_no_details),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
    val kind = auditActionPresentation(event.action, event.system).kind
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
                actionLabel(event.action, event.system),
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
        when (val itemState = event.resource.itemState().takeIf { event.stateDescribesItem() }) {
            is AuditItemState.InTrash ->
                Text(
                    inTrashText(itemState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("admin-audit-detail-in-trash"),
                )
            AuditItemState.Gone ->
                Text(
                    stringResource(R.string.admin_detail_gone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            AuditItemState.Active,
            null -> Unit
        }
    }
    DetailLine(label = stringResource(R.string.admin_detail_who)) {
        val actorRef = event.auditActorRef()
        if (actorRef.kind == UserKind.Unknown) {
            Text(
                stringResource(R.string.admin_actor_unknown),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            HistoryActor(event, actorRef, UserLabelSize.Regular, by = false)
        }
    }
    event.lifecycleOwnerRef()?.let { owner ->
        DetailLine(label = stringResource(R.string.sharing_owner)) {
            UserLabel(
                owner,
                size = UserLabelSize.Compact,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    DetailLine(label = stringResource(R.string.admin_detail_when)) {
        Text(whenText, style = MaterialTheme.typography.bodyLarge)
    }
    DetailLine(label = stringResource(R.string.admin_detail_client)) {
        AuditClientChip(event.client, large = true)
    }
    event.client
        ?.version
        ?.takeIf { it.isNotBlank() }
        ?.let { version ->
            DetailLine(label = stringResource(R.string.admin_detail_client_version)) {
                Text(version, style = MaterialTheme.typography.bodyLarge)
            }
        }
    event.client
        ?.userAgent
        ?.takeIf { it.isNotBlank() }
        ?.let { agent ->
            DetailLine(label = stringResource(R.string.admin_detail_client_agent)) {
                Text(
                    agent,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("admin-audit-detail-user-agent"),
                )
            }
        }
}

/** The client of an event: its icon and label, the same chip as on the website. */
@Composable
private fun AuditClientChip(client: AuditClientDto?, large: Boolean = false) {
    val kind = client.clientKind()
    val resources = LocalResources.current
    val label = client.clientLabel { resources.getString(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("admin-audit-client"),
    ) {
        Icon(
            kind.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (large) 18.dp else 14.dp),
        )
        Text(
            label,
            style =
                if (large) MaterialTheme.typography.bodyLarge
                else MaterialTheme.typography.labelSmall,
            color =
                if (large) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "In the trash · 85 days left". */
@Composable
private fun inTrashText(state: AuditItemState.InTrash): String =
    stringResource(R.string.admin_state_in_trash, daysLeftText(daysLeft(state.daysRemaining)))

/** The way to the trash for an item in it: the Trash screen, and Restore when allowed. */
@Composable
private fun AuditTrashActions(
    event: AuditEventDto,
    onOpenTrash: () -> Unit,
    onRestore: (AuditEventDto) -> Unit,
) {
    if (event.resource.itemState() !is AuditItemState.InTrash) return
    val restoreRef = event.restoreRef()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (restoreRef != null) {
            Button(
                onClick = { onRestore(event) },
                modifier = Modifier.testTag("admin-audit-restore"),
            ) {
                Text(stringResource(R.string.trash_restore))
            }
        }
        OutlinedButton(
            onClick = onOpenTrash,
            modifier = Modifier.testTag("admin-audit-open-trash"),
        ) {
            Text(stringResource(R.string.admin_open_trash))
        }
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
 * One changed field, before -> after, like the website's ChangeList: tinted -/+ panels joined by an
 * arrow, with only the changed words highlighted (removed struck through, added bold).
 */
@Composable
internal fun ChangeCard(item: ChangeItemDto) {
    val resources = LocalResources.current
    val string: (Int) -> String = { resources.getString(it) }
    val beforeValue = parseChangeValue(item.before)
    val afterValue = parseChangeValue(item.after)
    val beforeEmpty = beforeValue.isEmptyValue()
    val afterEmpty = afterValue.isEmptyValue()
    val before = beforeValue.displayText(string, item.field)
    val after = afterValue.displayText(string, item.field)
    val diff =
        remember(before, after, beforeEmpty, afterEmpty) {
            WordDiff.diff(if (beforeEmpty) "" else before, if (afterEmpty) "" else after)
        }
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
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                fieldLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            DiffBlock(
                label = beforeLabel,
                sign = "\u2212",
                text = before,
                segments = diff.before,
                empty = beforeEmpty,
                removed = true,
            )
            Text(
                "\u2193",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).clearAndSetSemantics {},
            )
            DiffBlock(
                label = afterLabel,
                sign = "+",
                text = after,
                segments = diff.after,
                empty = afterEmpty,
                removed = false,
            )
        }
    }
}

@Composable
private fun DiffBlock(
    label: String,
    sign: String,
    text: String,
    segments: List<DiffSegment>,
    empty: Boolean,
    removed: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val tone = if (removed) scheme.error else AuditTone.Green.color()
    val highlight =
        if (removed) scheme.errorContainer.copy(alpha = 0.7f) else tone.copy(alpha = 0.22f)
    val annotated =
        remember(segments, removed, highlight, tone) {
            buildAnnotatedString {
                segments.forEach { segment ->
                    if (!segment.changed) {
                        append(segment.text)
                    } else {
                        val style =
                            if (removed) {
                                SpanStyle(
                                    color = tone,
                                    background = highlight,
                                    textDecoration = TextDecoration.LineThrough,
                                )
                            } else {
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    background = highlight,
                                )
                            }
                        withStyle(style) { append(segment.text) }
                    }
                }
            }
        }
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(tone.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $text" }
    ) {
        Text("$sign $label", style = MaterialTheme.typography.labelSmall, color = tone)
        if (empty) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
            )
        } else {
            Text(
                annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
    }
}
