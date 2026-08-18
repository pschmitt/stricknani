package blue.anika.wolle.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Shown on the next launch after an uncaught exception (SNA-35) - see `crash/CrashReport.kt`. */
@Composable
fun CrashReportDialog(
    report: String,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text("The app recovered from a crash") },
        text = {
            Column {
                Text(
                    "A diagnostic report was saved from the previous run. It contains no API token.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        report,
                        modifier =
                            Modifier.padding(top = 12.dp)
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text("Copy report", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Text("Restart app", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
    )
}
