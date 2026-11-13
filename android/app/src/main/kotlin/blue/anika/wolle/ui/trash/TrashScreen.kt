package blue.anika.wolle.ui.trash

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.TrashItemDto
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.ui.admin.AdminFailureState
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.EveryoneMenu
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.UserKind
import blue.anika.wolle.ui.common.UserLabelSize
import blue.anika.wolle.ui.common.UserRef
import blue.anika.wolle.ui.common.UserSentence
import blue.anika.wolle.ui.common.YarnLoader
import coil3.compose.AsyncImage

/** The trash (de: Papierkorb): deleted items wait here to be restored or deleted forever. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    onOpenProject: (Int) -> Unit = {},
    onOpenYarn: (Int) -> Unit = {},
    onOpenTrashed: (kind: String, id: Int) -> Unit = { _, _ -> },
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var toPurge by remember { mutableStateOf<TrashItemDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                actions = { EveryoneMenu() },
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
        Column(Modifier.fillMaxSize().padding(innerPadding).testTag("trash-screen")) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val phase = state.phase) {
                    TrashPhase.Loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            YarnLoader()
                        }
                    is TrashPhase.Failed ->
                        AdminFailureState(
                            failure = phase.failure.asAdminFailure(),
                            onRetry = viewModel::retry,
                            onBack = onBack,
                        )
                    TrashPhase.Ready ->
                        PullToRefreshBox(
                            isRefreshing = state.isRefreshing,
                            onRefresh = viewModel::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (state.items.isEmpty()) {
                                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    EmptyState(
                                        icon = PhosphorIcons.Trash,
                                        title = stringResource(R.string.trash_empty_title),
                                        subtitle =
                                            stringResource(
                                                R.string.trash_empty_subtitle,
                                                state.retentionDays,
                                            ),
                                    )
                                }
                            } else {
                                TrashList(
                                    state,
                                    viewModel,
                                    onPurge = { toPurge = it },
                                    onOpenProject,
                                    onOpenYarn,
                                    onOpenTrashed,
                                )
                            }
                        }
                }
            }
        }
    }

    toPurge?.let { item ->
        val row = trashRow(item, showOwner = false)
        val kind = stringResource(row.kindLabelRes)
        AlertDialog(
            modifier = Modifier.testTag("trash-purge-dialog"),
            onDismissRequest = { toPurge = null },
            title = { Text(stringResource(R.string.trash_purge_dialog_title)) },
            text = { Text(stringResource(R.string.trash_purge_dialog_text, row.name ?: kind)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        toPurge = null
                        viewModel.purge(item)
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    modifier = Modifier.testTag("trash-purge-confirm"),
                ) {
                    Text(stringResource(R.string.trash_purge))
                }
            },
            dismissButton = {
                TextButton(onClick = { toPurge = null }) {
                    Text(stringResource(R.string.trash_keep))
                }
            },
        )
    }
}

/** The panel of a failed load: the shared admin panels cover offline, forbidden and the rest. */
internal fun TrashFailure.asAdminFailure(): AdminFailure =
    when (this) {
        TrashFailure.Offline -> AdminFailure.Offline
        TrashFailure.NotAllowed -> AdminFailure.NotAllowed
        TrashFailure.NotFound,
        TrashFailure.Unsupported -> AdminFailure.NotFound
        else -> AdminFailure.Other
    }

@Composable
private fun TrashList(
    state: TrashState,
    viewModel: TrashViewModel,
    onPurge: (TrashItemDto) -> Unit,
    onOpenProject: (Int) -> Unit,
    onOpenYarn: (Int) -> Unit,
    onOpenTrashed: (kind: String, id: Int) -> Unit,
) {
    val showOwner = state.scope == TrashScope.All
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("trash-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.trash_intro, state.retentionDays),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        state.restored?.let { restored ->
            item(key = "restored") {
                RestoredNote(restored, onOpenProject, onOpenYarn)
            }
        }
        items(state.items, key = { "${it.kind}:${it.id}" }) { item ->
            TrashRow(
                row = trashRow(item, showOwner, currentUserId),
                busy = state.busy != null,
                thumbnailUrl = viewModel.avatarUrl(item.thumbnailUrl),
                onRestore = { viewModel.restore(item) },
                onPurge = { onPurge(item) },
                parentId = item.parent?.id,
                onOpenProject = onOpenProject,
                onOpenYarn = onOpenYarn,
                // A trashed project or yarn opens read-only; the other kinds have no page.
                onOpen =
                    if (item.kind == "project" || item.kind == "yarn") {
                        { onOpenTrashed(item.kind, item.id.toInt()) }
                    } else null,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TrashRow(
    row: TrashRowModel,
    busy: Boolean,
    thumbnailUrl: String?,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onOpen: (() -> Unit)?,
    parentId: Long?,
    onOpenProject: (Int) -> Unit,
    onOpenYarn: (Int) -> Unit,
) {
    val kind = stringResource(row.kindLabelRes)
    val deletedAgo = trashDeletedAgo(row.deletedAt)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (onOpen != null)
                        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen)
                    else Modifier
                )
                .testTag("trash-row"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrashThumbnail(row.ref.kind, thumbnailUrl)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        row.name ?: kind,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        kind,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.parentKind?.let { parentKind ->
                        row.parentName?.let { parentName ->
                            if (
                                parentId != null &&
                                    (parentKind == "project" || parentKind == "yarn")
                            ) {
                                TextButton(
                                    onClick = {
                                        if (parentKind == "project") onOpenProject(parentId.toInt())
                                        else onOpenYarn(parentId.toInt())
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.primary
                                        ),
                                    modifier = Modifier.testTag("trash-parent-link"),
                                ) {
                                    Text(
                                        stringResource(trashParentRes(parentKind), parentName),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(trashParentRes(parentKind), parentName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    row.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    row.ownerName?.let { owner ->
                        UserSentence(
                            R.string.trash_owner,
                            UserRef(UserKind.User, owner, row.ownerAvatarUrl),
                            size = UserLabelSize.Compact,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        listOfNotNull(
                                deletedAgo?.let { stringResource(R.string.trash_deleted_when, it) },
                                daysLeftText(row.daysLeft),
                            )
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (row.daysLeft is DaysLeft.Due) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.canRestore) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = !busy,
                        modifier = Modifier.testTag("trash-restore"),
                    ) {
                        Icon(
                            PhosphorIcons.ClockCounterClockwise,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(stringResource(R.string.trash_restore))
                    }
                    TextButton(
                        onClick = onPurge,
                        enabled = !busy,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                        modifier = Modifier.testTag("trash-purge"),
                    ) {
                        Text(stringResource(R.string.trash_purge))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.trash_cannot_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trash-cannot-restore"),
                )
            }
        }
    }
}

/**
 * The item's cover or picture (the server's owner-scoped media URL, which also serves what is in
 * the trash); an icon for kinds without one, or while the picture cannot be loaded.
 */
@Composable
internal fun TrashThumbnail(kind: String, url: String?, size: Int = 56) {
    val icon = trashKindIcon(kind)
    Box(
        modifier =
            Modifier.size(size.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .testTag("trash-thumb"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun trashKindIcon(kind: String): ImageVector =
    when (kind) {
        "project" -> PhosphorIcons.Hoodie
        "yarn" -> MdiIcons.YarnBall
        "category" -> PhosphorIcons.FolderSimple
        "step" -> PhosphorIcons.ListNumbers
        "attachment" -> PhosphorIcons.Paperclip
        "image",
        "photo" -> PhosphorIcons.Images
        else -> PhosphorIcons.Trash
    }

/** "5 days left" or "Due for deletion". */
@Composable
fun daysLeftText(left: DaysLeft): String =
    when (left) {
        is DaysLeft.Days -> pluralStringResource(DAYS_LEFT_PLURAL, left.days, left.days)
        DaysLeft.Due -> stringResource(R.string.trash_due)
    }

/** "3 days ago", like the website's relative time; null when the timestamp is unreadable. */
@Composable
private fun trashDeletedAgo(deletedAt: String?): String? {
    val millis = trashDeletedMillis(deletedAt) ?: return null
    return DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        .toString()
}

/** "Restored X." with a link to open it again (projects and yarns only), like the website. */
@Composable
private fun RestoredNote(
    restored: TrashItemDto,
    onOpenProject: (Int) -> Unit,
    onOpenYarn: (Int) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("trash-restored"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        ) {
            Text(
                stringResource(
                    R.string.trash_restored_name,
                    restored.name ?: stringResource(trashKindLabelRes(restored.kind)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            when (restored.kind) {
                "project" ->
                    TextButton(onClick = { onOpenProject(restored.id.toInt()) }) {
                        Text(stringResource(R.string.trash_open_project))
                    }
                "yarn" ->
                    TextButton(onClick = { onOpenYarn(restored.id.toInt()) }) {
                        Text(stringResource(R.string.trash_open_yarn))
                    }
            }
        }
    }
}
