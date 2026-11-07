package blue.anika.wolle.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.backup.BackupFrequency
import blue.anika.wolle.data.export.DataExportViewModel
import blue.anika.wolle.data.export.DataRestoreViewModel
import blue.anika.wolle.data.export.ExportFailure
import blue.anika.wolle.data.export.ExportFormat
import blue.anika.wolle.data.export.ExportJobStatus
import blue.anika.wolle.data.export.RestoreFailure
import blue.anika.wolle.data.export.RestoreJobStatus
import blue.anika.wolle.data.export.countLine
import blue.anika.wolle.data.export.failureMessageRes
import blue.anika.wolle.data.export.isProcessing
import blue.anika.wolle.data.export.joinSummary
import blue.anika.wolle.data.export.restoreFailureMessageRes
import blue.anika.wolle.data.export.uploadPercent
import blue.anika.wolle.ui.common.FeedbackKind
import blue.anika.wolle.ui.common.NetworkStatus
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.showFeedback
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    exportViewModel: DataExportViewModel = hiltViewModel(),
    restoreViewModel: DataRestoreViewModel = hiltViewModel(),
) {
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val scheduledEnabled by viewModel.scheduledBackupEnabled.collectAsStateWithLifecycle()
    val scheduledFolderUri by viewModel.scheduledBackupFolderUri.collectAsStateWithLifecycle()
    val scheduledFrequency by viewModel.scheduledBackupFrequency.collectAsStateWithLifecycle()
    val scheduledPasswordSet by viewModel.scheduledBackupPasswordSet.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportStatus by exportViewModel.status.collectAsStateWithLifecycle()
    val exportFailure by exportViewModel.lastFailure.collectAsStateWithLifecycle()
    val restoreStatus by restoreViewModel.status.collectAsStateWithLifecycle()
    val restoreFailure by restoreViewModel.lastFailure.collectAsStateWithLifecycle()
    val restoreSummary by restoreViewModel.lastSummary.collectAsStateWithLifecycle()
    val restoreRunning = restoreStatus as? RestoreJobStatus.Running
    var pendingRestoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreName by rememberSaveable { mutableStateOf<String?>(null) }
    val dataRestoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Keep the read grant for the background job, which may start after this screen
                // is gone. Not every provider allows it; the job then relies on the one-time grant.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                pendingRestoreUri = uri.toString()
                pendingRestoreName = displayNameOf(context, uri)
            }
        }
    val savedMessage = stringResource(R.string.data_export_saved)
    // NetworkStatus is a plain flag, not a flow: look again every few seconds while on screen.
    var offline by remember { mutableStateOf(NetworkStatus.hasValidatedInternet == false) }
    LaunchedEffect(Unit) {
        while (true) {
            offline = NetworkStatus.hasValidatedInternet == false
            delay(CONNECTIVITY_POLL_MILLIS)
        }
    }
    var exportFormat by remember { mutableStateOf(ExportFormat.TarGz) }
    val onExportTarget = { uri: Uri? ->
        if (uri != null) {
            // Keep the grant for the background job, which may run after this screen is gone.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            exportViewModel.download(uri, displayNameOf(context, uri), exportFormat)
        }
    }
    val tarGzLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(ExportFormat.TarGz.mimeType),
            onExportTarget,
        )
    val zipLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(ExportFormat.Zip.mimeType),
            onExportTarget,
        )
    val startExport = { format: ExportFormat ->
        exportFormat = format
        val name = exportViewModel.suggestedName(format)
        if (format == ExportFormat.Zip) zipLauncher.launch(name) else tarGzLauncher.launch(name)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var showScheduledPasswordDialog by remember { mutableStateOf(false) }
    // SNA-59: gate turning scheduled backup on with an un-skippable choice (set a password, or
    // explicitly continue without one) whenever no password is set yet - see the dialog below.
    // Enabling used to silently jump straight to the folder picker with no password prompt at
    // all, producing a plaintext export containing the live API token on every scheduled run.
    var showScheduledPasswordGateDialog by remember { mutableStateOf(false) }

    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            pendingExportUri = uri
        }

    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importBackup(it) }
        }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { viewModel.enableScheduledBackup(it) }
        }

    LaunchedEffect(Unit) {
        exportViewModel.saved.collect { snackbarHostState.showFeedback(savedMessage) }
    }

    LaunchedEffect(pendingExportUri) {
        if (pendingExportUri != null) showExportPasswordDialog = true
    }

    LaunchedEffect(backupState) {
        when (val state = backupState) {
            is BackupOperationState.Success -> {
                snackbarHostState.showFeedback(state.message)
                viewModel.dismissBackupState()
            }
            is BackupOperationState.Error -> {
                snackbarHostState.showFeedback(state.message, FeedbackKind.Error)
                viewModel.dismissBackupState()
            }
            BackupOperationState.PasswordRequired -> showRestorePasswordDialog = true
            else -> Unit
        }
    }

    pendingRestoreUri?.let { uriString ->
        AlertDialog(
            onDismissRequest = {
                pendingRestoreUri = null
                pendingRestoreName = null
            },
            title = { Text(stringResource(R.string.data_restore_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.data_restore_dialog_text,
                        pendingRestoreName ?: stringResource(R.string.data_restore_file_unnamed),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreViewModel.restore(Uri.parse(uriString))
                        pendingRestoreUri = null
                        pendingRestoreName = null
                    }
                ) {
                    Text(stringResource(R.string.data_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingRestoreUri = null
                        pendingRestoreName = null
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Backup.title()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.data_export_title),
                    icon = Icons.Filled.Download,
                ) {
                    val running = exportStatus as? ExportJobStatus.Running
                    SettingsListItem(
                        headlineContent = { Text(stringResource(R.string.data_export_label)) },
                        supportingContent = {
                            Text(stringResource(R.string.data_export_description))
                        },
                    )
                    Text(
                        stringResource(R.string.data_export_archive_type),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .testTag("data-export-format")
                    ) {
                        ExportFormat.entries.forEachIndexed { index, format ->
                            SegmentedButton(
                                selected = exportFormat == format,
                                onClick = { exportFormat = format },
                                enabled = running == null && restoreRunning == null,
                                shape =
                                    SegmentedButtonDefaults.itemShape(
                                        index,
                                        ExportFormat.entries.size,
                                    ),
                            ) {
                                Text(".${format.extension}")
                            }
                        }
                    }
                    Button(
                        onClick = { startExport(exportFormat) },
                        enabled = running == null && restoreRunning == null && !offline,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("data-export-button"),
                    ) {
                        if (running != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    R.string.data_export_running,
                                    Formatter.formatShortFileSize(context, running.bytes),
                                )
                            )
                        } else {
                            Text(stringResource(R.string.data_export_button))
                        }
                    }
                    if (offline && running == null) {
                        Text(
                            stringResource(R.string.data_export_offline),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    exportFailure?.let { failure ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Text(
                                stringResource(failureMessageRes(failure)),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).testTag("data-export-error"),
                            )
                            // A 401 is handled by the session-expiry banner, where retrying is
                            // pointless until the user signs in again.
                            if (failure != ExportFailure.Unauthorized) {
                                TextButton(onClick = { startExport(exportFormat) }) {
                                    Text(stringResource(R.string.data_export_retry))
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.data_export_format),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingsListItem(
                        headlineContent = { Text(stringResource(R.string.data_restore_label)) },
                        supportingContent = {
                            Text(stringResource(R.string.data_restore_description))
                        },
                    )
                    Button(
                        onClick = { dataRestoreLauncher.launch(RESTORE_MIME_TYPES) },
                        enabled = running == null && restoreRunning == null && !offline,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("data-restore-button"),
                    ) {
                        if (restoreRunning != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val percent = uploadPercent(restoreRunning.sent, restoreRunning.total)
                            Text(
                                if (
                                    percent != null &&
                                        !isProcessing(restoreRunning.sent, restoreRunning.total)
                                )
                                    stringResource(R.string.data_restore_uploading, percent)
                                else stringResource(R.string.data_restore_processing)
                            )
                        } else {
                            Text(stringResource(R.string.backup_settings_choose_file_button))
                        }
                    }
                    if (offline && restoreRunning == null) {
                        Text(
                            stringResource(R.string.data_export_offline),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    restoreFailure?.let { failure ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Text(
                                stringResource(restoreFailureMessageRes(failure)),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).testTag("data-restore-error"),
                            )
                            // A 401 is handled by the session-expiry banner. Retrying means picking
                            // the file again: the grant of the first pick may be gone.
                            if (failure != RestoreFailure.Unauthorized) {
                                TextButton(
                                    onClick = { dataRestoreLauncher.launch(RESTORE_MIME_TYPES) }
                                ) {
                                    Text(stringResource(R.string.data_export_retry))
                                }
                            }
                        }
                    }
                    restoreSummary?.let { summary ->
                        val lines =
                            listOfNotNull(
                                countLine(
                                    stringResource(R.string.data_restore_projects),
                                    summary.projects,
                                ),
                                countLine(
                                    stringResource(R.string.data_restore_yarns),
                                    summary.yarns,
                                ),
                                countLine(
                                    stringResource(R.string.data_restore_media),
                                    summary.mediaFiles,
                                ),
                                if (summary.mediaSkipped > 0)
                                    countLine(
                                        stringResource(R.string.data_restore_missing),
                                        summary.mediaSkipped,
                                    )
                                else null,
                            )
                        Text(
                            joinSummary(lines),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    .testTag("data-restore-summary"),
                        )
                    }
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.backup_settings_manual_title),
                    icon = Icons.Filled.CloudUpload,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.backup_settings_export_label))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.backup_settings_export_description))
                        },
                    )
                    Button(
                        onClick = {
                            exportLauncher.launch(
                                "stricknani-backup-${
                                    DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                                        .replace(':', '-')
                                }.stricknanibackup"
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.backup_settings_export_button))
                    }
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.backup_settings_restore_label))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.backup_settings_restore_description))
                        },
                    )
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.backup_settings_choose_backup_file_button))
                    }
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.backup_settings_scheduled_title),
                    icon = PhosphorIcons.ClockCounterClockwise,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.backup_settings_enable_scheduled_label))
                        },
                        supportingContent = { Text(scheduledFrequency.label) },
                        trailingContent = {
                            Switch(
                                checked = scheduledEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        // SNA-59: only skip straight to the folder picker if a
                                        // password is already set (e.g. the user set one earlier
                                        // via "Change" below, or re-enabling after a previous
                                        // password-protected setup) - otherwise force the explicit
                                        // gate dialog first so an unencrypted, token-bearing
                                        // scheduled export can never happen silently.
                                        if (scheduledPasswordSet) {
                                            folderLauncher.launch(null)
                                        } else {
                                            showScheduledPasswordGateDialog = true
                                        }
                                    } else {
                                        viewModel.disableScheduledBackup()
                                    }
                                },
                            )
                        },
                    )
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.backup_settings_destination_folder_label))
                        },
                        supportingContent = {
                            Text(
                                scheduledFolderUri?.let {
                                    stringResource(R.string.backup_settings_folder_chosen)
                                } ?: stringResource(R.string.backup_settings_folder_not_set)
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        trailingContent = {
                            TextButton(onClick = { folderLauncher.launch(null) }) {
                                Text(stringResource(R.string.backup_settings_change_button))
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.backup_settings_frequency_label),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        BackupFrequency.entries.forEach { frequency ->
                            FilterChip(
                                selected = scheduledFrequency == frequency,
                                onClick = { viewModel.setScheduledBackupFrequency(frequency) },
                                label = { Text(frequency.label) },
                            )
                        }
                    }
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.backup_settings_password_label))
                        },
                        supportingContent = {
                            Text(
                                if (scheduledPasswordSet)
                                    stringResource(R.string.backup_settings_password_set)
                                else stringResource(R.string.backup_settings_password_not_set)
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Password, contentDescription = null) },
                        trailingContent = {
                            TextButton(onClick = { showScheduledPasswordDialog = true }) {
                                Text(stringResource(R.string.backup_settings_change_button))
                            }
                        },
                    )
                }
            }
        }
    }

    if (showExportPasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_settings_export_password_dialog_title),
            confirmLabel = stringResource(R.string.backup_settings_export_confirm),
            // SNA-62: the title above says "(optional)", but without this the Confirm button
            // stayed disabled for a blank password and Cancel aborted the export entirely - there
            // was no way to actually produce the unencrypted export the label promised. A manual
            // export is a deliberate, in-the-moment user action (unlike the scheduled/automatic
            // backup case fixed in SNA-59, which forces an explicit choice precisely because it
            // isn't), so honoring "optional" here rather than silently requiring a password is the
            // right fix - `viewModel.exportBackup` already treats a blank password as "no
            // password" (`BackupManager.export`/`BackupCrypto.encode`).
            allowBlankToClear = true,
            onDismiss = {
                showExportPasswordDialog = false
                pendingExportUri = null
            },
            onConfirm = { password ->
                showExportPasswordDialog = false
                pendingExportUri?.let { viewModel.exportBackup(it, password) }
                pendingExportUri = null
            },
        )
    }

    if (showRestorePasswordDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_settings_restore_password_dialog_title),
            confirmLabel = stringResource(R.string.backup_settings_restore_confirm),
            onDismiss = {
                showRestorePasswordDialog = false
                viewModel.dismissBackupState()
            },
            onConfirm = { password ->
                showRestorePasswordDialog = false
                viewModel.retryImportWithPassword(password)
            },
        )
    }

    if (showScheduledPasswordDialog) {
        val passwordRemovedMessage = stringResource(R.string.backup_settings_password_removed_toast)
        val passwordSetMessage = stringResource(R.string.backup_settings_password_set_toast)
        BackupPasswordDialog(
            title = stringResource(R.string.backup_settings_scheduled_password_dialog_title),
            confirmLabel = stringResource(R.string.common_save),
            allowBlankToClear = true,
            onDismiss = { showScheduledPasswordDialog = false },
            onConfirm = { password ->
                showScheduledPasswordDialog = false
                viewModel.setScheduledBackupPassword(password)
                scope.launch {
                    snackbarHostState.showFeedback(
                        if (password.isBlank()) passwordRemovedMessage else passwordSetMessage
                    )
                }
            },
        )
    }

    if (showScheduledPasswordGateDialog) {
        ScheduledBackupPasswordGateDialog(
            onDismiss = { showScheduledPasswordGateDialog = false },
            onSetPassword = { password ->
                showScheduledPasswordGateDialog = false
                viewModel.setScheduledBackupPassword(password)
                folderLauncher.launch(null)
            },
            onContinueWithoutPassword = {
                showScheduledPasswordGateDialog = false
                folderLauncher.launch(null)
            },
        )
    }
}

/**
 * SNA-59: un-skippable choice shown the first time scheduled backup is enabled with no password
 * already set. Unlike [BackupPasswordDialog]'s dismiss/confirm shape, there is deliberately no
 * plain "OK" that silently proceeds unencrypted - continuing without a password is its own
 * explicitly-labelled action, separate from cancelling out of the dialog entirely (which leaves
 * scheduled backup off).
 */
@Composable
fun ScheduledBackupPasswordGateDialog(
    onDismiss: () -> Unit,
    onSetPassword: (String) -> Unit,
    onContinueWithoutPassword: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("scheduled-backup-password-gate"),
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_settings_scheduled_gate_title)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_settings_scheduled_gate_warning))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_settings_password_field_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("scheduled-backup-gate-password"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSetPassword(password) },
                enabled = password.isNotBlank(),
                modifier = Modifier.testTag("scheduled-backup-gate-set-password"),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text(stringResource(R.string.backup_settings_scheduled_gate_set_password_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onContinueWithoutPassword,
                modifier = Modifier.testTag("scheduled-backup-gate-skip"),
            ) {
                Text(stringResource(R.string.backup_settings_scheduled_gate_skip_button))
            }
        },
    )
}

@Composable
fun BackupPasswordDialog(
    title: String,
    confirmLabel: String,
    allowBlankToClear: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.backup_settings_password_field_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("backup-password-dialog-field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = allowBlankToClear || password.isNotBlank(),
                modifier = Modifier.testTag("backup-password-dialog-confirm"),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private const val CONNECTIVITY_POLL_MILLIS = 2_000L

/** What the picker offers: the archives a download produces, and providers that say nothing. */
private val RESTORE_MIME_TYPES =
    arrayOf("application/gzip", "application/x-gzip", "application/zip", "application/octet-stream")

/** The name the user gave the file in the picker, or null when the provider does not say. */
private fun displayNameOf(context: android.content.Context, uri: Uri): String? =
    runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }
        .getOrNull()
