package blue.anika.wolle.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                title = { Text(SettingsCategory.Backup.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                SettingsGroupCard(title = "Manual backup", icon = Icons.Filled.CloudUpload) {
                    SettingsListItem(
                        headlineContent = { Text("Export backup") },
                        supportingContent = {
                            Text("Server connection and app settings, optionally encrypted")
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
                        Text("Export now")
                    }
                    SettingsListItem(
                        headlineContent = { Text("Restore from backup") },
                        supportingContent = {
                            Text("Overwrites the server connection on this device")
                        },
                    )
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Choose file")
                    }
                }
            }

            item {
                SettingsGroupCard(title = "Scheduled backups", icon = Icons.Filled.History) {
                    SettingsListItem(
                        headlineContent = { Text("Enable scheduled backups") },
                        supportingContent = { Text(scheduledFrequency.label) },
                        trailingContent = {
                            Switch(
                                checked = scheduledEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        folderLauncher.launch(null)
                                    } else {
                                        viewModel.disableScheduledBackup()
                                    }
                                },
                            )
                        },
                    )
                    SettingsListItem(
                        headlineContent = { Text("Destination folder") },
                        supportingContent = {
                            Text(scheduledFolderUri?.let { "Chosen" } ?: "Not set")
                        },
                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        trailingContent = {
                            TextButton(onClick = { folderLauncher.launch(null) }) { Text("Change") }
                        },
                    )
                    Text(
                        "Frequency",
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
                        headlineContent = { Text("Backup password") },
                        supportingContent = {
                            Text(if (scheduledPasswordSet) "Set" else "Not set (unencrypted)")
                        },
                        leadingContent = { Icon(Icons.Filled.Password, contentDescription = null) },
                        trailingContent = {
                            TextButton(onClick = { showScheduledPasswordDialog = true }) {
                                Text("Change")
                            }
                        },
                    )
                }
            }
        }
    }

    if (showExportPasswordDialog) {
        BackupPasswordDialog(
            title = "Encrypt backup? (optional)",
            confirmLabel = "Export",
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
            title = "This backup is password-protected",
            confirmLabel = "Restore",
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
        BackupPasswordDialog(
            title = "Scheduled backup password",
            confirmLabel = "Save",
            allowBlankToClear = true,
            onDismiss = { showScheduledPasswordDialog = false },
            onConfirm = { password ->
                showScheduledPasswordDialog = false
                viewModel.setScheduledBackupPassword(password)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (password.isBlank()) "Backup password removed" else "Backup password set"
                    )
                }
            },
        )
    }
}

@Composable
private fun BackupPasswordDialog(
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
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = allowBlankToClear || password.isNotBlank(),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
