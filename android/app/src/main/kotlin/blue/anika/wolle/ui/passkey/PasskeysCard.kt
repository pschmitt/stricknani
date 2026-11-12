package blue.anika.wolle.ui.passkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.passkey.PasskeyDto
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.oidc.ReauthSheet
import blue.anika.wolle.ui.oidc.linkedDate
import blue.anika.wolle.ui.settings.SettingsGroupCard
import blue.anika.wolle.ui.settings.SettingsListItem

/**
 * "Passkeys" in Settings → Account: sign in with the phone's screen lock instead of typing the
 * password. Same wording as the web app. Draws nothing while the server has no passkeys.
 */
@Composable
fun PasskeysCard(viewModel: PasskeysViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val host = rememberPasskeyHost()
    if (state.phase == PasskeysPhase.Hidden) return

    // The confirmation for adding is done: the system sheet needs this screen's window.
    LaunchedEffect(state.addReady) { if (state.addReady) viewModel.completeAdd(host) }

    SettingsGroupCard(
        title = stringResource(R.string.passkeys_title),
        icon = PhosphorIcons.LockKey,
    ) {
        if (state.phase == PasskeysPhase.Failed) {
            SettingsListItem(
                headlineContent = { Text(stringResource(R.string.passkeys_load_failed)) },
                trailingContent = {
                    TextButton(onClick = viewModel::reload) {
                        Text(stringResource(R.string.passkeys_retry))
                    }
                },
            )
            return@SettingsGroupCard
        }

        Text(
            stringResource(R.string.passkeys_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (state.passkeys.isEmpty()) {
            SettingsListItem(headlineContent = { Text(stringResource(R.string.passkeys_empty)) })
        }
        state.passkeys.forEach { passkey ->
            PasskeyRow(
                passkey = passkey,
                enabled = !state.working,
                onRename = { viewModel.onRenameClicked(passkey) },
                onRemove = { viewModel.onRemoveClicked(passkey) },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.canAdd) {
                Button(
                    onClick = viewModel::onAddClicked,
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().testTag("passkey-add"),
                ) {
                    Text(
                        stringResource(
                            if (state.working) R.string.passkeys_waiting
                            else R.string.passkeys_add_button
                        )
                    )
                }
            } else {
                Text(
                    pluralStringResource(
                        R.plurals.passkeys_limit_reached,
                        state.limit,
                        state.limit,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    state.renaming?.let { passkey ->
        RenameDialog(
            current = passkey.nickname,
            onConfirm = viewModel::rename,
            onDismiss = viewModel::dismissRename,
        )
    }

    state.confirmRemove?.let { passkey ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemove,
            title = { Text(stringResource(R.string.passkeys_remove_title, passkey.nickname)) },
            text = { Text(stringResource(R.string.passkeys_remove_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemove) {
                    Text(
                        stringResource(R.string.passkeys_remove_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemove) {
                    Text(stringResource(R.string.passkeys_keep_button))
                }
            },
        )
    }

    sheet?.let {
        ReauthSheet(
            state = it,
            onSubmitPassword = viewModel::submitPassword,
            onUseProvider = {},
            onDismiss = viewModel::dismissSheet,
            message = R.string.passkeys_reauth_text,
            onUsePasskey = viewModel::confirmWithPasskey,
        )
    }
}

@Composable
private fun PasskeyRow(
    passkey: PasskeyDto,
    enabled: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    SettingsListItem(
        headlineContent = { Text(passkey.nickname) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.passkeys_added, linkedDate(passkey.createdAt)))
                Text(
                    passkey.lastUsedAt?.let {
                        stringResource(R.string.passkeys_last_used, linkedDate(it))
                    } ?: stringResource(R.string.passkeys_never_used)
                )
                Text(
                    stringResource(
                        if (passkey.backupState) R.string.passkeys_synced
                        else R.string.passkeys_one_device
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onRename,
                    enabled = enabled,
                    modifier = Modifier.testTag("passkey-rename-${passkey.id}"),
                ) {
                    Text(stringResource(R.string.passkeys_rename_button))
                }
                OutlinedButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.testTag("passkey-remove-${passkey.id}"),
                ) {
                    Text(stringResource(R.string.passkeys_remove_button))
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passkeys_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PasskeysViewModel.MAX_NAME_CHARS) },
                label = { Text(stringResource(R.string.passkeys_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("passkey-name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
