package blue.anika.wolle.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R

/** The sign-out confirmation shared by the Account screen and the More screen. */
@Composable
internal fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_settings_sign_out_dialog_title)) },
        text = { Text(stringResource(R.string.settings_sign_out_description)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_sign_out)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
