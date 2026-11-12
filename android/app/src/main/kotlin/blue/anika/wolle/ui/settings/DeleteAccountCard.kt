package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.oidc.ReauthSheet

/**
 * "Delete account" at the bottom of Settings → Account: a typed confirmation, then the same
 * "confirm it is you" sheet as linking a login, then the server deletes the account. [onDeleted]
 * wipes the local copy (like a sign-out), which returns the app to onboarding.
 */
@Composable
internal fun DeleteAccountCard(
    onDeleted: () -> Unit,
    viewModel: DeleteAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            onDeleted()
            viewModel.acknowledgeDeleted()
        }
    }

    SettingsSingleItemCard {
        Text(
            stringResource(R.string.delete_account_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Text(
            stringResource(R.string.delete_account_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )
        OutlinedButton(
            onClick = viewModel::open,
            colors =
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("account-delete-button"),
        ) {
            androidx.compose.material3.Icon(
                PhosphorIcons.Trash,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
            Text(stringResource(R.string.delete_account_button))
        }
    }

    if (state.open) {
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.delete_account_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.delete_account_dialog_text))
                    OutlinedTextField(
                        value = state.confirmation,
                        onValueChange = viewModel::onConfirmationChange,
                        label = {
                            Text(stringResource(R.string.delete_account_confirmation_label))
                        },
                        singleLine = true,
                        enabled = !state.busy,
                        isError = state.error != null,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Email,
                                autoCorrectEnabled = false,
                            ),
                        modifier = Modifier.fillMaxWidth().testTag("account-delete-confirmation"),
                    )
                    state.error?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirm,
                    enabled = !state.busy && viewModel.confirmationValid,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.testTag("account-delete-confirm"),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 0.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                    Text(
                        stringResource(R.string.delete_account_confirm),
                        modifier = Modifier.padding(start = if (state.busy) 8.dp else 0.dp),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss, enabled = !state.busy) {
                    Text(stringResource(R.string.delete_account_keep))
                }
            },
        )
    }

    sheet?.let {
        ReauthSheet(
            state = it,
            onSubmitPassword = viewModel::submitPassword,
            onUseProvider = viewModel::confirmWithProvider,
            onUsePasskey = viewModel::confirmWithPasskey,
            onDismiss = viewModel::dismissSheet,
            message = R.string.delete_account_reauth_text,
        )
    }
}
