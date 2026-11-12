package blue.anika.wolle.ui.admin

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.backups.BackupEntryDto
import blue.anika.wolle.data.backups.BackupKind
import blue.anika.wolle.data.backups.BackupListDto
import blue.anika.wolle.data.backups.CountKey
import blue.anika.wolle.data.backups.MediaOption
import blue.anika.wolle.data.backups.RestoreErrorKind
import blue.anika.wolle.data.backups.RestoreOutcomeDto
import blue.anika.wolle.data.backups.RestorePreviewDto
import blue.anika.wolle.data.backups.RestoreState
import blue.anika.wolle.data.backups.canRestore
import blue.anika.wolle.data.backups.countsDiffer
import blue.anika.wolle.data.backups.formatBackupBytes
import blue.anika.wolle.data.backups.formatBackupCount
import blue.anika.wolle.data.backups.formatBackupTime
import blue.anika.wolle.data.backups.isLocked
import blue.anika.wolle.data.backups.value
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.UserSentence
import blue.anika.wolle.ui.common.YarnLoader
import blue.anika.wolle.ui.common.accountRef
import blue.anika.wolle.ui.oidc.ReauthSheet
import blue.anika.wolle.ui.theme.BackupManualInk
import blue.anika.wolle.ui.theme.BackupManualInkNight
import blue.anika.wolle.ui.theme.BackupManualWash
import blue.anika.wolle.ui.theme.BackupManualWashNight
import blue.anika.wolle.ui.theme.BackupMigrationInk
import blue.anika.wolle.ui.theme.BackupMigrationInkNight
import blue.anika.wolle.ui.theme.BackupMigrationWash
import blue.anika.wolle.ui.theme.BackupMigrationWashNight
import blue.anika.wolle.ui.theme.BackupNightlyInk
import blue.anika.wolle.ui.theme.BackupNightlyInkNight
import blue.anika.wolle.ui.theme.BackupNightlyWash
import blue.anika.wolle.ui.theme.BackupNightlyWashNight
import blue.anika.wolle.ui.theme.BackupRestoreInk
import blue.anika.wolle.ui.theme.BackupRestoreInkNight
import blue.anika.wolle.ui.theme.BackupRestoreWash
import blue.anika.wolle.ui.theme.BackupRestoreWashNight
import blue.anika.wolle.ui.theme.BackupUpdateInk
import blue.anika.wolle.ui.theme.BackupUpdateInkNight
import blue.anika.wolle.ui.theme.BackupUpdateWash
import blue.anika.wolle.ui.theme.BackupUpdateWashNight
import blue.anika.wolle.ui.theme.WarningInk
import blue.anika.wolle.ui.theme.WarningInkNight
import blue.anika.wolle.ui.theme.WarningSurface
import blue.anika.wolle.ui.theme.WarningSurfaceNight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings -> Admin -> Backups. Web twin: `/app/admin/backups`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminBackupsScreen(onBack: () -> Unit, viewModel: AdminBackupsViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backups_title)) },
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
        Box(Modifier.fillMaxSize().padding(innerPadding).testTag("admin-backups-screen")) {
            when (val current = phase) {
                BackupsPhase.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        YarnLoader()
                    }
                is BackupsPhase.Failed ->
                    AdminFailureState(current.failure, onRetry = viewModel::retry, onBack = onBack)
                is BackupsPhase.Ready ->
                    if (!current.list.available)
                        Text(
                            stringResource(R.string.backups_unavailable),
                            modifier = Modifier.padding(16.dp),
                        )
                    else
                        BackupsList(
                            list = current.list,
                            backups = current.backups,
                            onRestore = viewModel::open,
                        )
            }
        }
    }

    RestoreDialog(state = flow, viewModel = viewModel)

    sheet?.let {
        ReauthSheet(
            state = it,
            onSubmitPassword = viewModel::submitPassword,
            onUseProvider = viewModel::confirmWithProvider,
            onDismiss = viewModel::dismissSheet,
            message = R.string.backups_reauth_text,
            onUsePasskey = viewModel::confirmWithPasskey,
        )
    }
}

/** One entry of the flattened list, so the LazyColumn stays lazy across the day groups. */
private sealed interface BackupListItem {
    data class Header(val label: DayLabel) : BackupListItem

    data class Row(val entry: BackupEntryDto) : BackupListItem
}

@Composable
private fun BackupsList(
    list: BackupListDto,
    backups: List<BackupEntryDto>,
    onRestore: (BackupEntryDto) -> Unit,
) {
    val undoTarget = remember(list, backups) { undoCandidate(list, backups) }
    val zone = remember { ZoneId.systemDefault() }
    val nowMillis = remember(list) { System.currentTimeMillis() }
    val rows =
        remember(backups, nowMillis) {
            groupBackupsByDay(backups, LocalDate.now(zone), zone).flatMap { group ->
                listOf<BackupListItem>(BackupListItem.Header(group.label)) +
                    group.entries.map { BackupListItem.Row(it) }
            }
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.pendingRestore?.let { pending ->
            item {
                Banner(tag = "backups-pending") {
                    Text(stringResource(R.string.backups_pending, pending.backup))
                }
            }
        }
        list.lastRestore?.let { last ->
            // The Undo card carries the success message; the plain banner covers the rest.
            if (undoTarget == null || !last.succeeded) {
                item {
                    Banner(problem = !last.succeeded, tag = "backups-last-restore") {
                        if (last.succeeded)
                            UserSentence(
                                R.string.backups_last_succeeded,
                                last.backup,
                                formatBackupTime(last.finishedAt),
                                accountRef(
                                    last.requestedByEmail,
                                    avatarUrl = last.requestedByAvatarUrl,
                                ),
                            )
                        else
                            Text(
                                stringResource(R.string.backups_last_failed, last.backup) +
                                    (last.error?.let { " $it" } ?: "")
                            )
                    }
                    last.mediaError?.let {
                        Text(
                            stringResource(R.string.backups_media_error, it),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        val last = list.lastRestore
        if (undoTarget != null && last != null) {
            item { UndoCard(last, undoTarget, onRestore) }
        }
        if (backups.isEmpty()) {
            item { Text(stringResource(R.string.backups_none)) }
        } else {
            items(
                rows,
                key = {
                    when (it) {
                        is BackupListItem.Header -> "day-${it.label}"
                        is BackupListItem.Row -> it.entry.name
                    }
                },
            ) { item ->
                when (item) {
                    is BackupListItem.Header ->
                        BackupDayHeader(
                            item.label,
                            Modifier.padding(top = if (item === rows.first()) 8.dp else 16.dp),
                        )
                    is BackupListItem.Row -> BackupRow(item.entry, nowMillis, onRestore)
                }
            }
        }
    }
}

@Composable
private fun BackupDayHeader(label: DayLabel, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val text =
        when (label) {
            DayLabel.Today -> stringResource(R.string.admin_day_today)
            DayLabel.Yesterday -> stringResource(R.string.admin_day_yesterday)
            is DayLabel.On ->
                DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                    .withLocale(locale)
                    .format(label.date)
            DayLabel.Unknown -> stringResource(R.string.admin_day_unknown)
        }
    Text(
        text.uppercase(locale),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp).semantics { heading() },
    )
}

@Composable
private fun UndoCard(
    last: RestoreOutcomeDto,
    target: BackupEntryDto,
    onRestore: (BackupEntryDto) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = if (dark) WarningInkNight else WarningInk
    val surface = if (dark) WarningSurfaceNight else WarningSurface
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = surface,
        border = BorderStroke(1.dp, ink),
        modifier = Modifier.fillMaxWidth().testTag("backups-undo"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Restore,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.backups_undo_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    UserSentence(
                        R.string.backups_undo_text,
                        accountRef(last.requestedByEmail, avatarUrl = last.requestedByAvatarUrl),
                        last.backup,
                        formatBackupTime(last.finishedAt),
                        target.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    last.mediaError?.let {
                        Text(
                            stringResource(R.string.backups_media_error, it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { onRestore(target) },
                enabled = target.restorable,
                modifier = Modifier.fillMaxWidth().testTag("backups-undo-action"),
            ) {
                Icon(Icons.Filled.Restore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.backups_undo_action))
            }
        }
    }
}

@Composable
private fun Banner(problem: Boolean = false, tag: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color =
            if (problem) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                if (problem) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface
        ) {
            Box(Modifier.padding(12.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BackupRow(entry: BackupEntryDto, nowMillis: Long, onRestore: (BackupEntryDto) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val instant =
        remember(entry.createdAt) { runCatching { Instant.parse(entry.createdAt) }.getOrNull() }
    val clock =
        instant?.let {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
                .format(it)
        } ?: entry.createdAt
    val age =
        instant
            ?.toEpochMilli()
            ?.takeIf { nowMillis - it in 0..AGE_LIMIT_MILLIS }
            ?.let {
                DateUtils.getRelativeTimeSpanString(
                        it,
                        nowMillis,
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    )
                    .toString()
            }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("backup-row"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KindBadge(entry.kindOrManual)
                Text(
                    clock,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (age != null)
                    Text(
                        age,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            // The full name shows on long-press when the line is cut.
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(entry.name) } },
                state = rememberTooltipState(),
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Fact(Icons.Filled.Inventory2, formatBackupBytes(entry.sizeBytes))
                entry.schemaVersion?.let {
                    Fact(Icons.Filled.Tag, stringResource(R.string.backups_schema, it.toString()))
                }
                entry.counts?.let { counts ->
                    Fact(
                        PhosphorIcons.UserCircle,
                        stringResource(
                            R.string.backups_count_users,
                            formatBackupCount(counts.users),
                        ),
                    )
                    Fact(
                        PhosphorIcons.Hoodie,
                        stringResource(
                            R.string.backups_count_projects,
                            formatBackupCount(counts.projects),
                        ),
                    )
                    Fact(
                        PhosphorIcons.Yarn,
                        stringResource(
                            R.string.backups_count_yarns,
                            formatBackupCount(counts.yarns),
                        ),
                    )
                    Fact(
                        PhosphorIcons.Trash,
                        stringResource(
                            R.string.backups_count_trashed,
                            formatBackupCount(counts.trashed),
                        ),
                    )
                }
                if (entry.hasMedia)
                    Fact(PhosphorIcons.Images, stringResource(R.string.backups_has_media))
            }
            val problem =
                when {
                    entry.newerSchema -> stringResource(R.string.backups_newer_schema)
                    !entry.readable -> stringResource(R.string.backups_unreadable)
                    else -> null
                }
            problem?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        PhosphorIcons.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            OutlinedButton(
                onClick = { onRestore(entry) },
                enabled = entry.restorable,
                modifier = Modifier.fillMaxWidth().testTag("backup-restore"),
            ) {
                Icon(Icons.Filled.Restore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.backups_restore_action))
            }
        }
    }
}

/** Older backups read as a plain date by the day header; no "5 weeks ago" beside them. */
private const val AGE_LIMIT_MILLIS = 31L * 24 * 60 * 60 * 1000

/** A small icon-and-text chip of a backup's metadata. */
@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(percent = 50),
                )
                .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** The ink and wash of a [BackupTone] in the current (light or dark) theme. */
@Composable
internal fun BackupTone.colors(): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (this) {
        BackupTone.Nightly ->
            if (dark) BackupNightlyInkNight to BackupNightlyWashNight
            else BackupNightlyInk to BackupNightlyWash
        BackupTone.Update ->
            if (dark) BackupUpdateInkNight to BackupUpdateWashNight
            else BackupUpdateInk to BackupUpdateWash
        BackupTone.Migration ->
            if (dark) BackupMigrationInkNight to BackupMigrationWashNight
            else BackupMigrationInk to BackupMigrationWash
        BackupTone.Restore ->
            if (dark) BackupRestoreInkNight to BackupRestoreWashNight
            else BackupRestoreInk to BackupRestoreWash
        BackupTone.Manual ->
            if (dark) BackupManualInkNight to BackupManualWashNight
            else BackupManualInk to BackupManualWash
    }
}

@Composable
private fun KindBadge(kind: BackupKind) {
    val style = kind.style()
    val (ink, wash) = style.tone.colors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.background(wash, CircleShape)
                .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Icon(
            style.icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(style.label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = ink,
            maxLines = 1,
        )
    }
}

@StringRes
private fun RestoreErrorKind.messageRes(): Int =
    when (this) {
        RestoreErrorKind.NEWER_SCHEMA -> R.string.backups_error_newer_schema
        RestoreErrorKind.UNREADABLE -> R.string.backups_error_unreadable
        RestoreErrorKind.BUSY -> R.string.backups_error_busy
        RestoreErrorKind.PRE_RESTORE_FAILED -> R.string.backups_error_pre_restore
        RestoreErrorKind.STAGE_FAILED -> R.string.backups_error_stage
        RestoreErrorKind.CONFIRMATION -> R.string.backups_error_confirmation
        RestoreErrorKind.MEDIA -> R.string.backups_error_media
        RestoreErrorKind.UNAVAILABLE -> R.string.backups_unavailable
        RestoreErrorKind.OFFLINE,
        RestoreErrorKind.UNKNOWN -> R.string.backups_error_generic
    }

@Composable
private fun RestoreDialog(state: RestoreState, viewModel: AdminBackupsViewModel) {
    if (state is RestoreState.Closed) return
    val title =
        when (state) {
            is RestoreState.Succeeded -> R.string.backups_dialog_done
            is RestoreState.Failed -> R.string.backups_dialog_failed
            is RestoreState.Waiting,
            is RestoreState.Restoring -> R.string.backups_dialog_restoring
            else -> R.string.backups_dialog_title
        }
    AlertDialog(
        onDismissRequest = { if (!state.isLocked()) closeFor(state, viewModel) },
        title = { Text(stringResource(title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag("restore-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RestoreBody(state, viewModel)
            }
        },
        confirmButton = { RestoreConfirmButton(state, viewModel) },
        dismissButton = { RestoreDismissButton(state, viewModel) },
    )
}

private fun closeFor(state: RestoreState, viewModel: AdminBackupsViewModel) {
    when (state) {
        is RestoreState.Succeeded,
        is RestoreState.Failed,
        is RestoreState.Timeout,
        is RestoreState.ManualRestart -> viewModel.finish()
        else -> viewModel.close()
    }
}

private fun RestoreState.backupName(): String? =
    when (this) {
        is RestoreState.Loading -> backup.name
        is RestoreState.Preview -> backup.name
        is RestoreState.Reauth -> backup.name
        is RestoreState.Confirm -> backup.name
        is RestoreState.Restoring -> backup.name
        is RestoreState.Waiting -> backup.name
        is RestoreState.ManualRestart -> backup.name
        is RestoreState.Succeeded -> backup.name
        is RestoreState.Failed -> backup.name
        is RestoreState.Timeout -> backup.name
        is RestoreState.Error -> backup.name
        RestoreState.Closed -> null
    }

@Composable
private fun RestoreBody(state: RestoreState, viewModel: AdminBackupsViewModel) {
    state.backupName()?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    when (state) {
        is RestoreState.Loading -> Text(stringResource(R.string.backups_dialog_comparing))
        is RestoreState.Error -> ProblemText(stringResource(state.error.messageRes()))
        is RestoreState.Preview ->
            PreviewBody(state.backup, state.preview, state.media, viewModel, mediaEnabled = true)
        is RestoreState.Reauth ->
            PreviewBody(state.backup, state.preview, state.media, viewModel, mediaEnabled = false)
        is RestoreState.Confirm -> {
            PreviewBody(state.backup, state.preview, state.media, viewModel, mediaEnabled = true)
            state.error?.let { ProblemText(stringResource(it.messageRes())) }
            ConfirmField(state.typed, state.backup.name, enabled = true, viewModel)
        }
        is RestoreState.Restoring -> {
            PreviewBody(state.backup, state.preview, state.media, viewModel, mediaEnabled = false)
            ConfirmField(state.typed, state.backup.name, enabled = false, viewModel)
        }
        is RestoreState.Waiting -> {
            Text(stringResource(R.string.backups_dialog_waiting))
            Text(
                stringResource(R.string.backups_dialog_keep_open),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        is RestoreState.Succeeded -> {
            Text(stringResource(R.string.backups_dialog_succeeded))
            Text(
                stringResource(R.string.backups_dialog_saved_as, state.accepted.preRestore),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is RestoreState.Failed -> {
            ProblemText(stringResource(R.string.backups_dialog_server_failed))
            state.error?.let { Text(it, fontFamily = FontFamily.Monospace) }
        }
        is RestoreState.Timeout -> ProblemText(stringResource(R.string.backups_dialog_timeout))
        is RestoreState.ManualRestart -> Text(stringResource(R.string.backups_dialog_manual))
        RestoreState.Closed -> Unit
    }
}

@Composable
private fun ProblemText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PreviewBody(
    backup: BackupEntryDto,
    preview: RestorePreviewDto,
    media: MediaOption,
    viewModel: AdminBackupsViewModel,
    mediaEnabled: Boolean,
) {
    Text(
        stringResource(R.string.backups_dialog_taken_on, formatBackupTime(backup.createdAt)) +
            " " +
            formatBackupBytes(backup.sizeBytes)
    )
    Row(Modifier.fillMaxWidth()) {
        Text("", Modifier.weight(1.4f))
        Text(
            stringResource(R.string.backups_dialog_now),
            Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.backups_dialog_this_backup),
            Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
        )
    }
    listOf(
            CountKey.USERS to R.string.backups_row_accounts,
            CountKey.PROJECTS to R.string.backups_row_projects,
            CountKey.YARNS to R.string.backups_row_yarns,
            CountKey.TRASHED to R.string.backups_row_trashed,
        )
        .forEach { (key, label) ->
            val differs = countsDiffer(preview.current.counts, preview.backupSnapshot.counts, key)
            val weight = if (differs) FontWeight.Bold else FontWeight.Normal
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(label), Modifier.weight(1.4f), fontWeight = FontWeight.SemiBold)
                Text(
                    formatBackupCount(preview.current.counts.value(key)),
                    Modifier.weight(1f),
                    fontWeight = weight,
                )
                Text(
                    formatBackupCount(preview.backupSnapshot.counts.value(key)),
                    Modifier.weight(1f),
                    fontWeight = weight,
                )
            }
        }
    val time = formatBackupTime(backup.createdAt)
    val changes = preview.changesSinceBackup
    Text(
        if (changes != null && changes > 0)
            stringResource(R.string.backups_dialog_lost_with_edits, time, changes.toString())
        else stringResource(R.string.backups_dialog_lost, time),
        fontWeight = FontWeight.SemiBold,
    )
    listOf(
            stringResource(R.string.backups_dialog_effect_accounts),
            stringResource(R.string.backups_dialog_effect_devices),
            stringResource(R.string.backups_dialog_effect_safety_copy),
            if (preview.restartsServer) stringResource(R.string.backups_dialog_effect_restart)
            else stringResource(R.string.backups_dialog_effect_manual),
        )
        .forEach {
            Text(
                "• $it",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    if (MediaOption.ADD_MISSING in preview.options) {
        Text(stringResource(R.string.backups_dialog_media), fontWeight = FontWeight.SemiBold)
        MediaRadio(
            stringResource(R.string.backups_dialog_media_keep),
            selected = media == MediaOption.NONE,
            enabled = mediaEnabled,
            tag = "restore-media-none",
        ) {
            viewModel.setMedia(MediaOption.NONE)
        }
        MediaRadio(
            stringResource(R.string.backups_dialog_media_add),
            selected = media == MediaOption.ADD_MISSING,
            enabled = mediaEnabled,
            tag = "restore-media-add",
        ) {
            viewModel.setMedia(MediaOption.ADD_MISSING)
        }
    }
}

@Composable
private fun MediaRadio(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ConfirmField(
    typed: String,
    name: String,
    enabled: Boolean,
    viewModel: AdminBackupsViewModel,
) {
    OutlinedTextField(
        value = typed,
        onValueChange = viewModel::onTyped,
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.backups_dialog_type_name)) },
        placeholder = { Text(name) },
        modifier = Modifier.fillMaxWidth().testTag("restore-confirmation"),
    )
}

@Composable
private fun RestoreConfirmButton(state: RestoreState, viewModel: AdminBackupsViewModel) {
    when (state) {
        is RestoreState.Preview,
        is RestoreState.Reauth ->
            Button(onClick = viewModel::proceed, modifier = Modifier.testTag("restore-continue")) {
                Text(stringResource(R.string.backups_dialog_continue))
            }
        is RestoreState.Confirm,
        is RestoreState.Restoring ->
            Button(
                onClick = viewModel::submit,
                enabled = state.canRestore(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier = Modifier.testTag("restore-now"),
            ) {
                Text(
                    stringResource(
                        if (state is RestoreState.Restoring) R.string.backups_dialog_preparing
                        else R.string.backups_dialog_restore_now
                    )
                )
            }
        is RestoreState.Timeout ->
            TextButton(onClick = viewModel::checkAgain) {
                Text(stringResource(R.string.backups_dialog_check_again))
            }
        is RestoreState.Succeeded ->
            Button(onClick = viewModel::finish, modifier = Modifier.testTag("restore-reload")) {
                Text(stringResource(R.string.backups_dialog_reload))
            }
        is RestoreState.Failed,
        is RestoreState.ManualRestart ->
            Button(onClick = viewModel::finish) {
                Text(stringResource(R.string.backups_dialog_close))
            }
        else -> Unit
    }
}

@Composable
private fun RestoreDismissButton(state: RestoreState, viewModel: AdminBackupsViewModel) {
    when (state) {
        is RestoreState.Loading,
        is RestoreState.Error ->
            TextButton(onClick = viewModel::close, enabled = state is RestoreState.Error) {
                Text(stringResource(R.string.backups_dialog_close))
            }
        is RestoreState.Preview,
        is RestoreState.Reauth ->
            TextButton(onClick = viewModel::close) {
                Text(stringResource(R.string.backups_dialog_cancel))
            }
        is RestoreState.Confirm,
        is RestoreState.Restoring ->
            TextButton(onClick = viewModel::back, enabled = state is RestoreState.Confirm) {
                Text(stringResource(R.string.backups_dialog_back))
            }
        is RestoreState.Timeout ->
            TextButton(onClick = viewModel::finish) {
                Text(stringResource(R.string.backups_dialog_close))
            }
        else -> Unit
    }
}
