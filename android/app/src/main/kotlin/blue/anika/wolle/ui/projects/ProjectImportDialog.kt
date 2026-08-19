package blue.anika.wolle.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

@Composable
fun ProjectImportDialog(
    visible: Boolean,
    state: ProjectImportState,
    onStart: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }

    if (!visible) {
        return
    }

    AlertDialog(
        modifier = Modifier.testTag("project-import-dialog"),
        onDismissRequest = onCancel,
        title = {
            Text(
                when (state) {
                    is ProjectImportState.Importing,
                    ProjectImportState.Saving -> stringResource(R.string.project_import_progress)
                    is ProjectImportState.AwaitingConfirmation ->
                        stringResource(R.string.project_import_confirmation_title)
                    is ProjectImportState.Completed ->
                        stringResource(R.string.project_import_success_title)
                    is ProjectImportState.Failed -> stringResource(R.string.project_import_error_title)
                    ProjectImportState.Idle -> stringResource(R.string.project_import_dialog_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    ProjectImportState.Idle ->
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.testTag("project-import-url"),
                            label = {
                                Text(stringResource(R.string.project_import_url_label))
                            },
                            placeholder = {
                                Text(stringResource(R.string.project_import_url_placeholder))
                            },
                            singleLine = true,
                        )
                    is ProjectImportState.Importing ->
                        ImportProgressContent(
                            text = stringResource(R.string.project_import_progress_description),
                            modifier = Modifier.testTag("project-import-progress"),
                        )
                    ProjectImportState.Saving ->
                        ImportProgressContent(
                            text = stringResource(R.string.project_import_saving_description),
                            modifier = Modifier.testTag("project-import-progress"),
                        )
                    is ProjectImportState.AwaitingConfirmation -> {
                        Text(
                            text =
                                stringResource(
                                    R.string.project_import_confirmation_text,
                                    state.preview.request.name,
                                )
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.project_import_steps_summary,
                                    state.preview.request.steps.size,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.preview.imageUrls.isNotEmpty()) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.project_import_images_summary,
                                        state.preview.imageUrls.size,
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.project_import_images_not_supported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.preview.aiFallback) {
                            Text(
                                text = stringResource(R.string.project_import_ai_fallback),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    is ProjectImportState.Completed -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.project_import_success_text,
                                    state.preview.request.name,
                                ),
                            modifier = Modifier.testTag("project-import-success"),
                        )
                    }
                    is ProjectImportState.Failed -> {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = importFailureMessage(state.reason),
                            modifier = Modifier.testTag("project-import-error"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                ProjectImportState.Idle ->
                    TextButton(
                        modifier = Modifier.testTag("project-import-start"),
                        onClick = { onStart(url) },
                    ) {
                        Text(stringResource(R.string.project_import_start))
                    }
                is ProjectImportState.AwaitingConfirmation ->
                    TextButton(
                        modifier = Modifier.testTag("project-import-confirm"),
                        onClick = onConfirm,
                    ) {
                        Text(stringResource(R.string.project_import_confirm_save))
                    }
                is ProjectImportState.Failed ->
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.project_import_retry))
                    }
                is ProjectImportState.Completed ->
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.project_import_done))
                    }
                is ProjectImportState.Importing,
                ProjectImportState.Saving -> Unit
            }
        },
        dismissButton = {
            when (state) {
                ProjectImportState.Idle,
                is ProjectImportState.AwaitingConfirmation,
                is ProjectImportState.Failed ->
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.common_cancel))
                    }
                is ProjectImportState.Importing ->
                    TextButton(
                        modifier = Modifier.testTag("project-import-cancel"),
                        onClick = onCancel,
                    ) {
                        Text(stringResource(R.string.project_import_cancel))
                    }
                ProjectImportState.Saving,
                is ProjectImportState.Completed -> Unit
            }
        },
    )

}

@Composable
private fun ImportProgressContent(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun importFailureMessage(reason: ProjectImportFailure): String =
    stringResource(
        when (reason) {
            ProjectImportFailure.InvalidUrl -> R.string.project_import_error_invalid_url
            ProjectImportFailure.Offline -> R.string.project_import_error_offline
            ProjectImportFailure.Server -> R.string.project_import_error_server
            ProjectImportFailure.Unknown -> R.string.project_import_error_unknown
        }
    )
