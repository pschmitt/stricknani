package blue.anika.wolle.ui.projects.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

/**
 * Print choices for a project: everything or only the instructions, with or without images.
 * [isPreparing] shows progress on the Print button while images download.
 */
@Composable
fun ProjectPrintDialog(
    isPreparing: Boolean,
    onPrint: (PrintOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by rememberSaveable { mutableStateOf(PrintContent.EVERYTHING) }
    var includeImages by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.print_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    listOf(
                            PrintContent.EVERYTHING to R.string.print_content_everything,
                            PrintContent.INSTRUCTIONS to R.string.print_content_instructions,
                        )
                        .forEach { (option, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .selectable(
                                            selected = content == option,
                                            onClick = { content = option },
                                            role = Role.RadioButton,
                                        )
                                        .padding(vertical = 4.dp),
                            ) {
                                RadioButton(selected = content == option, onClick = null)
                                Text(
                                    stringResource(label),
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = includeImages,
                                onValueChange = { includeImages = it },
                                role = Role.Switch,
                            )
                            .padding(vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.print_include_images),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = includeImages, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isPreparing,
                onClick = { onPrint(PrintOptions(content, includeImages)) },
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.print_preparing),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.print_confirm),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
