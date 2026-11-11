package blue.anika.wolle.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R
import kotlinx.coroutines.delay

const val DESTRUCTIVE_DELETE_ICON_TAG = "destructive-delete-icon"

/** Red delete glyph shared by editor buttons and detail overflow menus. */
@Composable
fun DestructiveDeleteIcon(contentDescription: String?, modifier: Modifier = Modifier) {
    Icon(
        imageVector = PhosphorIcons.Trash,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier.testTag(DESTRUCTIVE_DELETE_ICON_TAG),
    )
}

/** Shared confirmation UI for every destructive entity deletion. */
@Composable
fun DestructiveDeleteDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.common_delete),
) {
    // A tap in the first moments (a double tap on the menu entry) must not confirm the delete.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(DeleteConfirmation.ARM_DELAY_MILLIS)
        armed = true
    }
    AlertDialog(
        modifier = Modifier.testTag(DESTRUCTIVE_DELETE_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = armed,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error
                    ),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

const val DESTRUCTIVE_DELETE_DIALOG_TAG = "destructive-delete-dialog"
