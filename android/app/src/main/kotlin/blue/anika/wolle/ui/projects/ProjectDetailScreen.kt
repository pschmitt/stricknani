package blue.anika.wolle.ui.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ACCESS_OWNER
import blue.anika.wolle.data.api.dto.AttachmentDto
import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectStatus
import blue.anika.wolle.data.repository.ItemAccess
import blue.anika.wolle.data.repository.access
import blue.anika.wolle.data.repository.galleryImages
import blue.anika.wolle.data.repository.isCover
import blue.anika.wolle.data.settings.DetailCardDomain
import blue.anika.wolle.data.settings.DetailCardOrder
import blue.anika.wolle.data.settings.ProjectDetailCard
import blue.anika.wolle.data.timer.ProjectTimerReminderInterval
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.ui.common.ArchiveEntity
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon
import blue.anika.wolle.ui.common.DetailCardReorderHint
import blue.anika.wolle.ui.common.DetailTitle
import blue.anika.wolle.ui.common.ErrorKind
import blue.anika.wolle.ui.common.ErrorState
import blue.anika.wolle.ui.common.HtmlPrinter
import blue.anika.wolle.ui.common.ImageViewerDialog
import blue.anika.wolle.ui.common.ImageViewerImage
import blue.anika.wolle.ui.common.ItemBannerView
import blue.anika.wolle.ui.common.LocalOwnerContext
import blue.anika.wolle.ui.common.MarkdownImageTransformer
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.NotesDisclosure
import blue.anika.wolle.ui.common.OwnedKind
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.PhotoEditTarget
import blue.anika.wolle.ui.common.ProjectStatusBadge
import blue.anika.wolle.ui.common.ReadingTextScale
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.ReorderableDetailCard
import blue.anika.wolle.ui.common.ScrolledBarTitle
import blue.anika.wolle.ui.common.SectionActions
import blue.anika.wolle.ui.common.SourceRows
import blue.anika.wolle.ui.common.StickyNoteEmpty
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.TextSizeMenuSection
import blue.anika.wolle.ui.common.YarnLoader
import blue.anika.wolle.ui.common.copyLines
import blue.anika.wolle.ui.common.extractMarkdownImageReferences
import blue.anika.wolle.ui.common.formatUpdatedDate
import blue.anika.wolle.ui.common.itemBannerOf
import blue.anika.wolle.ui.common.markdownPreviewSource
import blue.anika.wolle.ui.common.normalizeMarkdownContent
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.rememberDetailCardReorderState
import blue.anika.wolle.ui.common.rememberRefreshFailure
import blue.anika.wolle.ui.common.shareUrl
import blue.anika.wolle.ui.history.HistoryController
import blue.anika.wolle.ui.history.HistoryPhase
import blue.anika.wolle.ui.history.HistorySection
import blue.anika.wolle.ui.history.NoHistory
import blue.anika.wolle.ui.print.PrintDialog
import blue.anika.wolle.ui.projects.print.PrintLabels
import blue.anika.wolle.ui.sharing.SharingDialogHost
import blue.anika.wolle.ui.sharing.rememberSharingDialogs
import blue.anika.wolle.ui.theme.stricknaniMarkdownTypography
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null,
    onYarnClick: (Int) -> Unit,
    onEditClick: (Int) -> Unit,
    onDeleted: () -> Unit,
    onEditSection: (projectId: Int, focus: String) -> Unit = { id, _ -> onEditClick(id) },
    viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val textStep by viewModel.textStep.collectAsStateWithLifecycle()
    val timer by viewModel.timerState.collectAsStateWithLifecycle()
    val timerSeconds by viewModel.timerSeconds.collectAsStateWithLifecycle()
    val projectTimersEnabled by viewModel.projectTimersEnabled.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel.projectId, projectTimersEnabled) {
        if (!projectTimersEnabled) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshTimer()
                delay(15_000)
            }
        }
    }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val cardOrder by
        viewModel.cardOrder.collectAsStateWithLifecycle(
            initialValue = DetailCardOrder.defaults(DetailCardDomain.PROJECT)
        )
    val snackbarHostState = remember { SnackbarHostState() }
    // The project the user asked to delete; a confirmation is only valid for this exact id.
    var deleteRequestId by remember { mutableStateOf<Int?>(null) }
    var attachmentToDelete by remember { mutableStateOf<AttachmentDto?>(null) }
    var showPrintDialog by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    val timerReminderDue =
        timer.running &&
            timer.reminderDeadlineMillis?.let { System.currentTimeMillis() >= it } == true
    // A due reminder opens the timer dialog, which asks whether to keep timing.
    LaunchedEffect(timerReminderDue) { if (timerReminderDue) showTimerDialog = true }
    val printOptions by viewModel.printOptions.collectAsStateWithLifecycle()
    var isPreparingPrint by remember { mutableStateOf(false) }
    val printScope = rememberCoroutineScope()
    val activityContext = LocalContext.current
    val printLabels =
        PrintLabels(
            details = stringResource(R.string.project_detail_details_title),
            category = stringResource(R.string.common_field_category),
            needles = stringResource(R.string.common_field_needles),
            yarn = stringResource(R.string.project_detail_field_yarn),
            otherMaterials = stringResource(R.string.common_field_other_materials),
            tags = stringResource(R.string.project_editor_tags_label),
            stitchSample = stringResource(R.string.common_field_stitch_sample),
            linkedYarns = stringResource(R.string.project_detail_linked_yarns_title),
            description = stringResource(R.string.project_detail_description_title),
            steps = stringResource(R.string.project_detail_steps_title),
            notes = stringResource(R.string.project_detail_notes_title),
        )

    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) { if (deleted) onDeleted() }

    // The full-screen error panel already says it; no snackbar on top of its buttons.
    RefreshFeedbackEffect(
        refreshState,
        snackbarHostState,
        viewModel::dismissRefreshFeedback,
        problemsShownInline =
            uiState is ProjectDetailUiState.NotFound || uiState is ProjectDetailUiState.LoadFailed,
    )

    val state = uiState
    val ownerContext = LocalOwnerContext.current
    val access = (state as? ProjectDetailUiState.Loaded)?.entity?.access() ?: ItemAccess.Own
    val banner =
        (state as? ProjectDetailUiState.Loaded)?.let {
            itemBannerOf(
                it.entity.ownerBadge(ownerContext.currentUserId),
                access,
                OwnedKind.Project,
            )
        }
    val sharingDialogs = rememberSharingDialogs()
    LaunchedEffect(access.canManage, state is ProjectDetailUiState.Loaded) {
        if (viewModel.sharingOffered && access.canManage && state is ProjectDetailUiState.Loaded)
            viewModel.sharing.preload()
    }
    val refreshFailure = rememberRefreshFailure(refreshState)
    if (showTimerDialog && projectTimersEnabled && !timer.unavailable) {
        ProjectTimerDialog(
            timer = timer,
            seconds = timerSeconds,
            canEdit = access.canEdit,
            projectDone =
                (uiState as? ProjectDetailUiState.Loaded)?.entity?.status == ProjectStatus.DONE,
            onReopen = viewModel::toggleStatus,
            reminderDue = timerReminderDue,
            onDismiss = { showTimerDialog = false },
            onRunningChange = viewModel::setTimerRunning,
            onEdit = { total, done -> viewModel.setTimerTotal(total, done) },
            onReminderChange = viewModel::setTimerReminderInterval,
            onReminderSnooze = viewModel::snoozeTimerReminder,
        )
    }
    // Hoisted so the top bar can show the name once the content title has scrolled away.
    val listState = remember { LazyListState() }
    Scaffold(
        snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        ScrolledBarTitle(
                            name =
                                if (state is ProjectDetailUiState.Loaded) state.entity.name else "",
                            listState = listState,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    },
                    actions = {
                        if (state is ProjectDetailUiState.Loaded) {
                            val context = LocalContext.current
                            var menuExpanded by remember { mutableStateOf(false) }
                            // The bar keeps only a running timer and More; Edit, Favorite, an idle
                            // timer, Share and Delete sit in the menu.
                            val timerAvailable = projectTimersEnabled && !timer.unavailable
                            if (timerAvailable && timer.running) {
                                ProjectTimerChip(
                                    totalSeconds = timerSeconds,
                                    running = timer.running,
                                    attention = timerReminderDue,
                                    onClick = { showTimerDialog = true },
                                )
                            }
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription =
                                        stringResource(
                                            R.string.project_detail_more_actions_description
                                        ),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                if (access.canEdit) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_edit)) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Edit, contentDescription = null)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onEditClick(state.entity.id)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (state.entity.isFavorite)
                                                    R.string.common_unfavorite
                                                else R.string.common_favorite
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (state.entity.isFavorite) Icons.Filled.Favorite
                                            else Icons.Filled.FavoriteBorder,
                                            contentDescription = null,
                                            tint =
                                                if (state.entity.isFavorite)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.toggleFavorite()
                                    },
                                )
                                if (access.canEdit) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (state.entity.status == ProjectStatus.DONE)
                                                        R.string.project_mark_in_progress
                                                    else R.string.project_mark_done
                                                )
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            viewModel.toggleStatus()
                                        },
                                    )
                                }
                                if (timerAvailable && !timer.running) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.project_timer_title))
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Timer, contentDescription = null)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            showTimerDialog = true
                                        },
                                    )
                                }
                                HorizontalDivider()
                                TextSizeMenuSection(
                                    step = textStep,
                                    onStepChange = viewModel::setTextStep,
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.print_action)) },
                                    leadingIcon = {
                                        Icon(PhosphorIcons.Printer, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showPrintDialog = true
                                    },
                                )
                                if (viewModel.sharingOffered) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sharing_share)) },
                                        leadingIcon = {
                                            Icon(
                                                PhosphorIcons.ShareNetwork,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            // Managers choose between the link and people in the
                                            // sheet;
                                            // everyone else just shares the link.
                                            if (access.canManage) sharingDialogs.sheet = true
                                            else
                                                viewModel.shareUrl()?.let { url ->
                                                    context.shareUrl(url)
                                                }
                                        },
                                    )
                                }
                                if (access.canLeave || access.canManage) HorizontalDivider()
                                if (access.canLeave) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sharing_leave)) },
                                        leadingIcon = {
                                            Icon(
                                                PhosphorIcons.UsersThree,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            sharingDialogs.leave = true
                                        },
                                    )
                                }
                                if (access.canManage) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.common_delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            DestructiveDeleteIcon(contentDescription = null)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            deleteRequestId = state.entity.id
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                banner?.let {
                    ItemBannerView(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when (state) {
                is ProjectDetailUiState.Loading ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().height(400.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                YarnLoader()
                            }
                        }
                    }
                is ProjectDetailUiState.LoadFailed ->
                    ErrorState(
                        kind = state.kind,
                        seed = "project-${viewModel.projectId}",
                        onRetry = viewModel::retryLoad,
                        onBack = onBack,
                        backLabel = stringResource(R.string.error_back_projects),
                        onHome = onHome,
                    )
                is ProjectDetailUiState.NotFound ->
                    ErrorState(
                        kind = refreshFailure ?: ErrorKind.ProjectMissing,
                        seed = "project-${viewModel.projectId}",
                        onRetry = if (refreshFailure != null) viewModel::refresh else null,
                        onBack = onBack,
                        backLabel = stringResource(R.string.error_back_projects),
                        onHome = onHome,
                    )
                is ProjectDetailUiState.Loaded ->
                    ReadingTextScale(textStep) {
                        ProjectDetailContent(
                            detail = state.detail,
                            linkedYarns = state.linkedYarns,
                            cardOrder = cardOrder,
                            onCardOrderChanged = viewModel::saveCardOrder,
                            resolveMediaUrl = viewModel::resolveMediaUrl,
                            onYarnClick = onYarnClick,
                            onDeleteAttachment = { attachmentToDelete = it },
                            onEditSection = { focus -> onEditSection(state.entity.id, focus) },
                            canEdit = access.canEdit,
                            // Archiving is owner-scoped on the server, like the web's control.
                            canArchive =
                                access.role == ACCESS_OWNER && !access.derived && access.canManage,
                            onArchived = viewModel::refresh,
                            history = viewModel.history,
                            listState = listState,
                        )
                    }
            }
        }
    }

    if (state is ProjectDetailUiState.Loaded) {
        val ownedLinkedYarnCount by viewModel.ownedLinkedYarnCount.collectAsStateWithLifecycle()
        SharingDialogHost(
            dialogs = sharingDialogs,
            controller = viewModel.sharing,
            isProject = true,
            itemName = state.entity.name,
            linkedYarnCount = state.detail.yarnIds.distinct().size,
            ownedLinkedYarnCount = ownedLinkedYarnCount,
            onShareLink = { viewModel.shareUrl()?.let { url -> activityContext.shareUrl(url) } },
        )
    }

    if (showPrintDialog && state is ProjectDetailUiState.Loaded) {
        PrintDialog(
            title = stringResource(R.string.print_dialog_title),
            initial = printOptions,
            offerContentChoice = true,
            isPreparing = isPreparingPrint,
            onPrint = { options ->
                isPreparingPrint = true
                printScope.launch {
                    val html = viewModel.printHtml(options, printLabels)
                    isPreparingPrint = false
                    showPrintDialog = false
                    if (html != null) HtmlPrinter.print(activityContext, state.entity.name, html)
                }
            },
            onDismiss = { if (!isPreparingPrint) showPrintDialog = false },
        )
    }

    if (deleteRequestId != null && state is ProjectDetailUiState.Loaded) {
        DestructiveDeleteDialog(
            title = stringResource(R.string.project_editor_delete_dialog_title, state.entity.name),
            text = stringResource(R.string.project_editor_delete_dialog_text),
            onConfirm = {
                val requested = deleteRequestId
                deleteRequestId = null
                if (requested != null) viewModel.delete(requested)
            },
            onDismiss = { deleteRequestId = null },
        )
    }

    attachmentToDelete?.let { attachment ->
        DestructiveDeleteDialog(
            title =
                stringResource(
                    R.string.project_attachment_delete_dialog_title,
                    attachment.originalFilename,
                ),
            text = stringResource(R.string.project_attachment_delete_dialog_text),
            onConfirm = {
                attachmentToDelete = null
                viewModel.deleteAttachment(attachment.id)
            },
            onDismiss = { attachmentToDelete = null },
        )
    }
}

@Composable
internal fun ProjectDetailContent(
    detail: ProjectDto,
    linkedYarns: List<LinkedYarn>,
    cardOrder: List<String>,
    onCardOrderChanged: (List<String>) -> Unit,
    resolveMediaUrl: (String?) -> String?,
    onYarnClick: (Int) -> Unit,
    onDeleteAttachment: (AttachmentDto) -> Unit,
    onEditSection: (focus: String) -> Unit,
    canEdit: Boolean,
    canArchive: Boolean,
    onArchived: () -> Unit,
    /** Null hides the history section (the read-only page of a trashed item). */
    history: HistoryController?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // A viewer (or a derived yarn) reads only: no Edit action, no section menu entry.
    val edit: ((String) -> Unit)? = if (canEdit) onEditSection else null
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val historyState by (history?.state ?: NoHistory).collectAsStateWithLifecycle()
    val viewerImages =
        remember(detail, resolveMediaUrl, canEdit) {
            projectViewerImages(detail, resolveMediaUrl, canEdit)
        }
    val openViewerImage: (String) -> Unit = { url ->
        viewerIndex = viewerImages.indexOfFirst { image -> image.url == url }.takeIf { it >= 0 }
    }
    val stitchSampleImages =
        remember(detail.images) { detail.images.filter { it.isStitchSample && it.stepId == null } }
    val availableCardKeys = buildList {
        // Always: like the web it shows at least when the project was updated.
        add(ProjectDetailCard.DETAILS)
        // Like the web, Materials and Pattern always show, with a placeholder when empty.
        add(ProjectDetailCard.MATERIALS)
        add(ProjectDetailCard.DESCRIPTION)
        if (detail.steps.isNotEmpty()) add(ProjectDetailCard.STEPS)
    }
    val savedVisibleOrder =
        remember(cardOrder, availableCardKeys) {
            DetailCardOrder.visible(DetailCardDomain.PROJECT, cardOrder, availableCardKeys)
        }
    var orderedCardKeys by remember(savedVisibleOrder) { mutableStateOf(savedVisibleOrder) }
    LaunchedEffect(savedVisibleOrder) { orderedCardKeys = savedVisibleOrder }
    val reorderState = rememberDetailCardReorderState()
    val itemIndexOffset =
        1 +
            (if (detail.images.isNotEmpty()) 1 else 0) +
            (if (detail.attachments.isNotEmpty()) 1 else 0) +
            1
    val moveCard: (Int, Int) -> Unit = { fromIndex, toIndex ->
        val next = DetailCardOrder.move(orderedCardKeys, fromIndex, toIndex)
        if (next != orderedCardKeys) {
            orderedCardKeys = next
            onCardOrderChanged(
                DetailCardOrder.withVisibleOrder(DetailCardDomain.PROJECT, cardOrder, next)
            )
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("e2e-project-detail"),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "detail-title") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailTitle(detail.name)
                    detail.category?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProjectStatusBadge(detail.status, Modifier.padding(vertical = 2.dp))
                    if (detail.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            detail.tags.forEach { tag ->
                                AssistChip(onClick = {}, label = { Text(tag) })
                            }
                        }
                    }
                }
            }
            if (detail.images.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(detail.galleryImages(), key = { _, image -> image.id }) {
                            _,
                            image ->
                            val url = resolveMediaUrl(image.url)
                            AsyncImage(
                                // SNA-36: reuse the already-remembered imageUrls instead of
                                // recomputing resolveMediaUrl per item.
                                model = url,
                                contentDescription = image.altText,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier.size(160.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            url?.let(openViewerImage)
                                        },
                            )
                        }
                    }
                }
            }
            if (detail.attachments.isNotEmpty()) {
                // Frequently used, so fixed directly under the gallery.
                item(key = "attachments") {
                    ProjectAttachmentsCard(
                        attachments = detail.attachments,
                        canEdit = canEdit,
                        onEdit = edit?.let { e -> { e(ProjectEditorFocus.ATTACHMENTS) } },
                        onDeleteAttachment = onDeleteAttachment,
                    )
                }
            }
            item { DetailCardReorderHint(reorderState, onDone = reorderState::finish) }

            items(orderedCardKeys, key = { it }) { cardKey ->
                val cardIndex = orderedCardKeys.indexOf(cardKey)
                when (cardKey) {
                    ProjectDetailCard.DETAILS ->
                        ReorderableDetailCard(
                            title = stringResource(R.string.project_detail_details_title),
                            icon = PhosphorIcons.Info,
                            onEdit = edit?.let { e -> { e(ProjectEditorFocus.DETAILS) } },
                            copyText =
                                (copyLines(
                                        stringResource(R.string.common_field_needles) to
                                            detail.needles,
                                        stringResource(R.string.common_field_stitch_sample) to
                                            detail.stitchSample,
                                    ) +
                                        listOfNotNull(detail.link?.takeIf { it.isNotBlank() })
                                            .joinToString("") { "\n$it" })
                                    .trimStart('\n'),
                            cardKey = cardKey,
                            cardIndex = cardIndex,
                            listState = listState,
                            orderedKeys = orderedCardKeys,
                            itemIndexOffset = itemIndexOffset,
                            reorderState = reorderState,
                            onMove = moveCard,
                        ) {
                            // The yarn is listed under Materials only.
                            detail.needles?.let {
                                DetailRow(
                                    stringResource(R.string.common_field_needles),
                                    it,
                                    icon = PhosphorIcons.Needle,
                                )
                            }
                            if (detail.stitchSample != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    Icon(
                                        PhosphorIcons.Ruler,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.common_field_stitch_sample),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                ProjectMarkdown(
                                    value = detail.stitchSample.orEmpty(),
                                    resolveMediaUrl = resolveMediaUrl,
                                    onImageClick = openViewerImage,
                                    keepLineBreaks = true,
                                )
                                StitchSampleImages(
                                    stitchSampleImages,
                                    resolveMediaUrl,
                                    openViewerImage,
                                )
                            }
                            SourceRows(
                                entity = ArchiveEntity.PROJECT,
                                id = detail.id,
                                link = detail.link,
                                linkArchive = detail.linkArchive,
                                openLabel = stringResource(R.string.project_detail_source_open),
                                archiveLabel =
                                    stringResource(R.string.project_detail_source_archive),
                                canArchive = canArchive,
                                onArchived = onArchived,
                            )
                            updatedDate(detail.updatedAt)?.let {
                                DetailRow(stringResource(R.string.common_field_updated), it)
                            }
                        }
                    ProjectDetailCard.MATERIALS -> {
                        var expanded by rememberSaveable { mutableStateOf(true) }
                        ReorderableDetailCard(
                            title = stringResource(R.string.project_detail_materials_title),
                            icon = MdiIcons.YarnBall,
                            onEdit = edit?.let { e -> { e(ProjectEditorFocus.LINKED_YARNS) } },
                            copyText =
                                (linkedYarns
                                        .map { it.name }
                                        .ifEmpty { listOfNotNull(detail.yarn) } +
                                        listOfNotNull(detail.otherMaterials))
                                    .joinToString("\n"),
                            cardKey = cardKey,
                            cardIndex = cardIndex,
                            listState = listState,
                            orderedKeys = orderedCardKeys,
                            itemIndexOffset = itemIndexOffset,
                            reorderState = reorderState,
                            onMove = moveCard,
                            // The heading's chevron opens and closes it, like the web's section.
                            headerAction = {
                                IconButton(
                                    onClick = { expanded = !expanded },
                                    modifier = Modifier.testTag("e2e-materials-toggle"),
                                ) {
                                    Icon(
                                        if (expanded) Icons.Filled.KeyboardArrowUp
                                        else Icons.Filled.KeyboardArrowDown,
                                        contentDescription =
                                            stringResource(
                                                if (expanded) R.string.project_detail_materials_hide
                                                else R.string.project_detail_materials_show
                                            ),
                                    )
                                }
                            },
                        ) {
                            if (expanded) {
                                // One tile per yarn: its row, and its notes (the amount)
                                // aligned under the name, like the web.
                                linkedYarns.forEachIndexed { index, yarn ->
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border =
                                            BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                            ),
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .padding(top = if (index > 0) 8.dp else 0.dp),
                                    ) {
                                        Column(
                                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            LinkedEntityRow(
                                                name = yarn.name,
                                                previewUrl = yarn.previewUrl?.let(resolveMediaUrl),
                                                fallbackIcon = MdiIcons.YarnBall,
                                                onClick = { onYarnClick(yarn.id) },
                                                subtitle = yarn.subtitle,
                                            )
                                            // The yarn's notes; no yarn source link here, the
                                            // yarn page has it.
                                            yarn.notes?.let {
                                                ProjectMarkdown(
                                                    value = it,
                                                    resolveMediaUrl = resolveMediaUrl,
                                                    onImageClick = openViewerImage,
                                                    // The picture (48dp) and its gap (12dp).
                                                    modifier = Modifier.padding(start = 60.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                // An older project's yarn text, when no yarn is linked (as on
                                // the web).
                                if (linkedYarns.isEmpty()) {
                                    DetailRow(
                                        stringResource(R.string.project_detail_field_yarn),
                                        detail.yarn
                                            ?: stringResource(R.string.project_detail_not_recorded),
                                        icon = PhosphorIcons.Yarn,
                                    )
                                }
                                detail.otherMaterials?.let {
                                    if (linkedYarns.isNotEmpty() || detail.yarn != null) {
                                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    }
                                    DetailRow(
                                        stringResource(R.string.common_field_other_materials),
                                        it,
                                        icon = PhosphorIcons.Scissors,
                                    )
                                }
                            }
                        }
                    }
                    ProjectDetailCard.DESCRIPTION ->
                        ReorderableDetailCard(
                            title = stringResource(R.string.project_detail_pattern_notes_title),
                            icon = PhosphorIcons.NotePencil,
                            onEdit = edit?.let { e -> { e(ProjectEditorFocus.DESCRIPTION) } },
                            copyText = detail.description,
                            cardKey = cardKey,
                            cardIndex = cardIndex,
                            listState = listState,
                            orderedKeys = orderedCardKeys,
                            itemIndexOffset = itemIndexOffset,
                            reorderState = reorderState,
                            onMove = moveCard,
                        ) {
                            Text(
                                stringResource(R.string.project_detail_pattern_reference),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("project-pattern-reference"),
                            )
                            val description = detail.description
                            if (description != null) {
                                ProjectMarkdown(
                                    value = description,
                                    resolveMediaUrl = resolveMediaUrl,
                                    onImageClick = openViewerImage,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.project_detail_no_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    ProjectDetailCard.STEPS ->
                        ReorderableDetailCard(
                            title = stringResource(R.string.project_detail_steps_title),
                            icon = PhosphorIcons.ListNumbers,
                            cardKey = cardKey,
                            cardIndex = cardIndex,
                            listState = listState,
                            orderedKeys = orderedCardKeys,
                            itemIndexOffset = itemIndexOffset,
                            reorderState = reorderState,
                            onMove = moveCard,
                        ) {
                            val sortedSteps =
                                remember(detail.steps) { detail.steps.sortedBy { it.stepNumber } }
                            sortedSteps.forEachIndexed { index, step ->
                                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                                // Each step has its own Edit / Copy text menu; Edit opens that
                                // step.
                                val stepHeading = "${step.stepNumber}. ${step.title}"
                                SectionActions(
                                    what = stepHeading,
                                    copyText =
                                        listOfNotNull(stepHeading, step.description)
                                            .joinToString("\n\n"),
                                    onEdit =
                                        edit?.let { e -> { e(ProjectEditorFocus.step(step.id)) } },
                                ) {
                                    Text(
                                        "${step.stepNumber}. ${step.title}",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    step.description?.let {
                                        ProjectMarkdown(
                                            value = it,
                                            resolveMediaUrl = resolveMediaUrl,
                                            onImageClick = openViewerImage,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                }
            }
            if (detail.notes == null && canEdit) {
                item(key = "notes-empty") {
                    StickyNoteEmpty(
                        label = stringResource(R.string.notes_add_empty),
                        onClick = { onEditSection(ProjectEditorFocus.NOTES) },
                    )
                }
            }
            // Fixed last section (not reorderable); hidden entirely on servers without history.
            if (history != null && historyState.phase != HistoryPhase.Hidden) {
                item(key = "history") {
                    HistorySection(
                        state = historyState,
                        controller = history,
                        emptyHint = R.string.history_empty_hint_project,
                    )
                }
            }
        }
        // The notes: the pinned mini sticky note on the right edge that unfolds into the full
        // note (the web's sticky note in the Pattern section).
        if (detail.notes != null) {
            NotesDisclosure(listState) {
                ProjectMarkdown(
                    value = detail.notes.orEmpty(),
                    resolveMediaUrl = resolveMediaUrl,
                    onImageClick = openViewerImage,
                )
            }
        }
    }

    viewerIndex?.let { index ->
        ImageViewerDialog(
            images = viewerImages,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

/**
 * The project timer's dialog, opened from the stopwatch chip in the top bar: a big animated
 * stopwatch with the time, Start/Stop and Edit time, the running reminder, and the reminder's
 * question when it is due (the screen opens the dialog then).
 */
@Composable
private fun ProjectTimerDialog(
    timer: ProjectDetailViewModel.TimerState,
    seconds: Long,
    canEdit: Boolean,
    projectDone: Boolean,
    onReopen: () -> Unit,
    reminderDue: Boolean,
    onDismiss: () -> Unit,
    onRunningChange: (Boolean, (Boolean) -> Unit) -> Unit,
    onEdit: (Long, (Boolean) -> Unit) -> Unit,
    onReminderChange: (Int) -> Unit,
    onReminderSnooze: () -> Unit,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showReopenDialog by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    val actionsEnabled =
        !timer.saving &&
            !timer.loading &&
            !timer.error &&
            !timer.unavailable &&
            !timer.projectMissing
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
        ) {
            Box {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Stopwatch(totalSeconds = seconds, running = timer.running, size = 120.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.project_timer_title).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatTimer(seconds),
                            style =
                                MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum",
                                ),
                        )
                        Text(
                            stringResource(
                                if (timer.running) R.string.project_timer_running_caption
                                else R.string.project_timer_ready_caption
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val problem =
                        when {
                            timer.projectMissing -> R.string.project_detail_not_found
                            timer.error -> R.string.project_timer_error
                            else -> null
                        }
                    if (problem != null)
                        Text(
                            stringResource(problem),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    if (reminderDue) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.project_timer_reminder_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(R.string.project_timer_reminder_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = onReminderSnooze) {
                                    Text(stringResource(R.string.project_timer_reminder_keep))
                                }
                            }
                        }
                    }
                    if (canEdit) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                enabled = actionsEnabled,
                                onClick = {
                                    saveFailed = false
                                    showEditDialog = true
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.project_timer_edit))
                            }
                            Button(
                                enabled = actionsEnabled,
                                onClick = {
                                    if (!timer.running && projectDone) showReopenDialog = true
                                    else onRunningChange(!timer.running) {}
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(
                                        if (timer.running) R.string.project_timer_stop
                                        else R.string.project_timer_start
                                    )
                                )
                            }
                        }
                        if (timer.running && timer.startedByMe) {
                            Box {
                                TextButton(
                                    enabled = actionsEnabled,
                                    onClick = { reminderMenu = true },
                                ) {
                                    Text(
                                        "${stringResource(R.string.project_timer_reminder)}: ${stringResource(reminderIntervalLabel(timer.reminderIntervalMinutes))}"
                                    )
                                }
                                DropdownMenu(
                                    expanded = reminderMenu,
                                    onDismissRequest = { reminderMenu = false },
                                ) {
                                    ProjectTimerReminderInterval.choices.forEach { interval ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(reminderIntervalLabel(interval))
                                                )
                                            },
                                            onClick = {
                                                reminderMenu = false
                                                onReminderChange(interval)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showReopenDialog) {
        AlertDialog(
            onDismissRequest = { showReopenDialog = false },
            text = { Text(stringResource(R.string.project_timer_reopen_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReopenDialog = false
                        onReopen()
                        onRunningChange(true) {}
                    }
                ) {
                    Text(stringResource(R.string.project_timer_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReopenDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (showEditDialog) {
        ProjectTimerEditDialog(
            totalSeconds = seconds,
            isSaving = timer.saving,
            saveFailed = saveFailed || timer.error,
            onDismiss = { if (!timer.saving) showEditDialog = false },
            onSave = { total ->
                onEdit(total) { success ->
                    saveFailed = !success
                    if (success) showEditDialog = false
                }
            },
        )
    }
}

@androidx.annotation.StringRes
private fun reminderIntervalLabel(minutes: Int): Int =
    when (minutes) {
        ProjectTimerReminderInterval.OFF -> R.string.project_timer_reminder_off
        ProjectTimerReminderInterval.MINUTES_30 -> R.string.project_timer_reminder_30m
        ProjectTimerReminderInterval.HOURS_2 -> R.string.project_timer_reminder_2h
        else -> R.string.project_timer_reminder_1h
    }

@Composable
private fun ProjectTimerEditDialog(
    totalSeconds: Long,
    isSaving: Boolean,
    saveFailed: Boolean,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var hours by remember { mutableStateOf((totalSeconds / 3600).toString()) }
    var minutes by remember { mutableStateOf(((totalSeconds % 3600) / 60).toString()) }
    var seconds by remember { mutableStateOf((totalSeconds % 60).toString()) }
    val parsed = listOf(hours, minutes, seconds).map { it.toLongOrNull() }
    val valid =
        parsed.all { it != null } &&
            (parsed[1] ?: 60) in 0..59 &&
            (parsed[2] ?: 60) in 0..59 &&
            ((parsed[0] ?: Long.MAX_VALUE) * 3600 + (parsed[1] ?: 0) * 60 + (parsed[2] ?: 0)) <=
                315_576_000L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_timer_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimerField(
                        hours,
                        { hours = it },
                        R.string.project_timer_hours,
                        Modifier.weight(1f),
                    )
                    TimerField(
                        minutes,
                        { minutes = it },
                        R.string.project_timer_minutes,
                        Modifier.weight(1f),
                    )
                    TimerField(
                        seconds,
                        { seconds = it },
                        R.string.project_timer_seconds,
                        Modifier.weight(1f),
                    )
                }
                if (!valid || saveFailed)
                    Text(
                        stringResource(
                            if (saveFailed) R.string.project_timer_error
                            else R.string.project_timer_invalid
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !isSaving,
                onClick = {
                    val h = parsed[0]!!
                    val m = parsed[1]!!
                    val s = parsed[2]!!
                    onSave(h * 3600 + m * 60 + s)
                },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun TimerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(stringResource(label)) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun formatTimer(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
private fun ProjectMarkdown(
    value: String,
    resolveMediaUrl: (String?) -> String?,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    keepLineBreaks: Boolean = false,
) {
    val content =
        remember(value, keepLineBreaks) {
            markdownPreviewSource(normalizeMarkdownContent(value), keepLineBreaks)
        }
    val references = remember(content) { extractMarkdownImageReferences(content) }
    val imageTransformer =
        remember(resolveMediaUrl, references, onImageClick) {
            MarkdownImageTransformer(
                resolveUrl = resolveMediaUrl,
                onImageClick = onImageClick,
                imageReferences = references,
            )
        }
    Markdown(
        content = content,
        typography = stricknaniMarkdownTypography(),
        imageTransformer = imageTransformer,
        modifier = modifier,
    )
}

private fun projectViewerImages(
    detail: ProjectDto,
    resolveMediaUrl: (String?) -> String?,
    canEdit: Boolean = false,
): List<ImageViewerImage> {
    val images = linkedMapOf<String, ImageViewerImage>()
    val stepsById = detail.steps.associateBy { it.id }
    detail.galleryImages().forEach { image ->
        val sourceLabel =
            when {
                image.isCover() -> "Project title image"
                image.isStitchSample && image.stepId == null -> "Stitch sample"
                image.stepId != null ->
                    stepsById[image.stepId]?.let { "Step ${it.stepNumber}: ${it.title}" }
                        ?: "Project step image"
                else -> "Project image"
            }
        resolveMediaUrl(image.url)?.let { url ->
            images.putIfAbsent(
                url,
                ImageViewerImage(
                    url = url,
                    title = detail.name,
                    sourceLabel = sourceLabel,
                    altText = image.altText,
                    editTarget =
                        if (canEdit) PhotoEditTarget.ProjectImage(detail.id, image.id) else null,
                ),
            )
        }
    }
    val markdownContexts = buildList {
        detail.stitchSample?.let { add("Stitch sample" to it) }
        detail.description?.let { add("Project description" to it) }
        detail.steps.forEach { step ->
            step.description?.let { add("Step ${step.stepNumber}: ${step.title}" to it) }
        }
        detail.notes?.let { add("Notes" to it) }
    }
    markdownContexts.forEach { (context, content) ->
        extractMarkdownImageReferences(normalizeMarkdownContent(content)).forEach { reference ->
            resolveMediaUrl(reference.source)?.let { url ->
                images.putIfAbsent(
                    url,
                    ImageViewerImage(
                        url = url,
                        title = detail.name,
                        sourceLabel = context,
                        altText = reference.altText,
                    ),
                )
            }
        }
    }
    return images.values.toList()
}

/**
 * A tappable row with a thumbnail-or-fallback-icon leading avatar, used for linked-entity lists.
 */
@Composable
internal fun LinkedEntityRow(
    name: String,
    previewUrl: String?,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier.size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        fallbackIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, icon: ImageVector? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 8.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The "Updated" value of a detail card, like the web; null if the timestamp does not parse. */
internal fun updatedDate(raw: String): String? =
    runCatching { formatUpdatedDate(DateTimeUtils.parseToEpochMillis(raw)) }.getOrNull()

/** The attachments, a fixed card under the gallery: they are opened and removed often. */
@Composable
private fun ProjectAttachmentsCard(
    attachments: List<AttachmentDto>,
    canEdit: Boolean,
    onEdit: (() -> Unit)?,
    onDeleteAttachment: (AttachmentDto) -> Unit,
) {
    val title = stringResource(R.string.project_detail_attachments_title)
    val accent = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth().testTag("e2e-project-attachments"),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PhosphorIcons.Paperclip,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            SectionActions(
                what = title,
                copyText = attachments.joinToString("\n") { it.originalFilename },
                onEdit = onEdit,
            ) {
                attachments.forEachIndexed { index, attachment ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            attachment.originalFilename,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (canEdit) {
                            IconButton(onClick = { onDeleteAttachment(attachment) }) {
                                DestructiveDeleteIcon(
                                    contentDescription =
                                        stringResource(
                                            R.string.project_detail_delete_attachment_description
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The stitch sample's own pictures, a row under its text. */
@Composable
private fun StitchSampleImages(
    images: List<ImageDto>,
    resolveMediaUrl: (String?) -> String?,
    onImageClick: (String) -> Unit,
) {
    if (images.isEmpty()) return
    LazyRow(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(images, key = { _, image -> image.id }) { _, image ->
            val url = resolveMediaUrl(image.url)
            AsyncImage(
                model = url,
                contentDescription = image.altText,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)).clickable {
                        url?.let(onImageClick)
                    },
            )
        }
    }
}
