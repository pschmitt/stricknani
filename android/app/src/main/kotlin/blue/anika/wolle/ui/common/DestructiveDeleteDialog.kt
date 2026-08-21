package blue.anika.wolle.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R

const val DESTRUCTIVE_DELETE_ICON_TAG = "destructive-delete-icon"

/** Red delete glyph shared by editor buttons and detail overflow menus. */
@Composable
fun DestructiveDeleteIcon(contentDescription: String?, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Delete,
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
) {
    AlertDialog(
        modifier = Modifier.testTag(DESTRUCTIVE_DELETE_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error
                    ),
            ) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

const val DESTRUCTIVE_DELETE_DIALOG_TAG = "destructive-delete-dialog"
