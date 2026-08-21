package blue.anika.wolle.ui.settings

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.backup.BackupFrequency
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val scheduledEnabled by viewModel.scheduledBackupEnabled.collectAsStateWithLifecycle()
    val scheduledFolderUri by viewModel.scheduledBackupFolderUri.collectAsStateWithLifecycle()
    val scheduledFrequency by viewModel.scheduledBackupFrequency.collectAsStateWithLifecycle()
    val scheduledPasswordSet by viewModel.scheduledBackupPasswordSet.collectAsStateWithLifecycle()

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

    LaunchedEffect(pendingExportUri) {
        if (pendingExportUri != null) showExportPasswordDialog = true
    }

    LaunchedEffect(backupState) {
        when (val state = backupState) {
            is BackupOperationState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.dismissBackupState()
            }
            is BackupOperationState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.dismissBackupState()
            }
            BackupOperationState.PasswordRequired -> showRestorePasswordDialog = true
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        Text(stringResource(R.string.backup_settings_choose_file_button))
                    }
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.backup_settings_scheduled_title),
                    icon = Icons.Filled.History,
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
                    snackbarHostState.showSnackbar(
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
