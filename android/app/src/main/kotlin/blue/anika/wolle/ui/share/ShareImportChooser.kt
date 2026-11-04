package blue.anika.wolle.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

/**
 * Shown when a link was shared to the app: choose whether to import it as a project or a yarn. The
 * choice only opens the import dialog with the link filled in; the user still confirms there.
 */
@Composable
fun ShareImportChooser(
    stage: ShareStage,
    onChoose: (ShareImportKind, String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (stage) {
        is ShareStage.Choose ->
            AlertDialog(
                modifier = Modifier.testTag("share-import-chooser"),
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.share_import_title)) },
                text = { LinkSummary(stage.url) },
                confirmButton = {
                    TextButton(
                        onClick = { onChoose(ShareImportKind.Project, stage.url) },
                        modifier = Modifier.testTag("share-import-project"),
                    ) {
                        Text(stringResource(R.string.share_import_project))
                    }
                },
                dismissButton = {
                    Column {
                        TextButton(
                            onClick = { onChoose(ShareImportKind.Yarn, stage.url) },
                            modifier = Modifier.testTag("share-import-yarn"),
                        ) {
                            Text(stringResource(R.string.share_import_yarn))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                },
            )
        is ShareStage.Unavailable ->
            AlertDialog(
                modifier = Modifier.testTag("share-import-unavailable"),
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.share_import_unavailable_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.import_unavailable_url))
                        LinkSummary(stage.url)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                },
            )
        ShareStage.None,
        is ShareStage.AwaitingSignIn -> Unit
    }
}

@Composable
private fun LinkSummary(url: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(SharedLink.hostOf(url), style = MaterialTheme.typography.titleSmall)
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
