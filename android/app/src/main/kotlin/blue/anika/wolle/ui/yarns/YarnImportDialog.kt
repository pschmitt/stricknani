package blue.anika.wolle.ui.yarns

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ImportAvailability
import blue.anika.wolle.ui.common.ImportAvailabilityNotes
import blue.anika.wolle.ui.common.ImportImagePicker
import blue.anika.wolle.ui.projects.ProjectImportFailure
import blue.anika.wolle.ui.projects.importWarningRes

/** "Import from a link" for yarns: paste a shop or Ravelry link, review, add to the stash. */
@Composable
fun YarnImportDialog(
    visible: Boolean,
    state: YarnImportState,
    onStart: (String) -> Unit,
    onConfirm: (List<String>) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    availability: ImportAvailability = ImportAvailability(),
    thumbnailUrl: (String) -> String? = { null },
) {
    var url by rememberSaveable { mutableStateOf("") }
    val preselected = (state as? YarnImportState.AwaitingConfirmation)?.preview?.request?.imageUrls
    var selectedImages by remember(state) { mutableStateOf(preselected.orEmpty().toSet()) }

    if (!visible) {
        return
    }

    AlertDialog(
        modifier = Modifier.testTag("yarn-import-dialog"),
        onDismissRequest = onCancel,
        title = {
            Text(
                when (state) {
                    is YarnImportState.Importing,
                    YarnImportState.Saving -> stringResource(R.string.yarn_import_progress)
                    is YarnImportState.AwaitingConfirmation ->
                        stringResource(R.string.project_import_confirmation_title)
                    is YarnImportState.Completed ->
                        stringResource(R.string.project_import_success_title)
                    is YarnImportState.Failed -> stringResource(R.string.project_import_error_title)
                    YarnImportState.Idle -> stringResource(R.string.yarn_import_dialog_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    YarnImportState.Idle -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            enabled = availability.url,
                            modifier = Modifier.testTag("yarn-import-url"),
                            label = { Text(stringResource(R.string.yarn_import_url_label)) },
                            placeholder = {
                                Text(stringResource(R.string.yarn_import_url_placeholder))
                            },
                            singleLine = true,
                        )
                        ImportAvailabilityNotes(availability)
                    }
                    is YarnImportState.Importing ->
                        ImportProgress(
                            text = stringResource(R.string.yarn_import_progress_description),
                            modifier = Modifier.testTag("yarn-import-progress"),
                        )
                    YarnImportState.Saving ->
                        ImportProgress(
                            text = stringResource(R.string.yarn_import_saving_description),
                            modifier = Modifier.testTag("yarn-import-progress"),
                        )
                    is YarnImportState.AwaitingConfirmation -> {
                        val request = state.preview.request
                        Text(
                            text =
                                stringResource(R.string.yarn_import_confirmation_text, request.name)
                        )
                        val summary =
                            listOfNotNull(
                                    request.brand,
                                    request.colorway,
                                    request.fiberContent,
                                    request.weightCategory,
                                    request.weightGrams?.let { "$it g" },
                                    request.lengthMeters?.let { "$it m" },
                                )
                                .joinToString(" · ")
                        if (summary.isNotEmpty()) {
                            Text(
                                text = summary,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.preview.warnings
                            .mapNotNull { importWarningRes(it) }
                            .forEach { res ->
                                Text(
                                    text = stringResource(res),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        if (state.preview.aiFields.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.import_ai_filled_hint),
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
                        if (state.preview.imageUrls.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.import_pick_photos),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ImportImagePicker(
                                urls = state.preview.imageUrls,
                                selected = selectedImages,
                                onToggle = { picked ->
                                    selectedImages =
                                        if (picked in selectedImages) selectedImages - picked
                                        else selectedImages + picked
                                },
                                thumbnailUrl = thumbnailUrl,
                            )
                        }
                    }
                    is YarnImportState.Completed -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.yarn_import_success_text,
                                    state.preview.request.name,
                                ),
                            modifier = Modifier.testTag("yarn-import-success"),
                        )
                    }
                    is YarnImportState.Failed -> {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = yarnImportFailureMessage(state.reason),
                            modifier = Modifier.testTag("yarn-import-error"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                YarnImportState.Idle ->
                    TextButton(
                        modifier = Modifier.testTag("yarn-import-start"),
                        enabled = availability.url,
                        onClick = { onStart(url) },
                    ) {
                        Text(stringResource(R.string.project_import_start))
                    }
                is YarnImportState.AwaitingConfirmation ->
                    TextButton(
                        modifier = Modifier.testTag("yarn-import-confirm"),
                        onClick = {
                            onConfirm(state.preview.imageUrls.filter { it in selectedImages })
                        },
                    ) {
                        Text(stringResource(R.string.yarn_import_confirm_save))
                    }
                is YarnImportState.Failed ->
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.project_import_retry))
                    }
                is YarnImportState.Completed ->
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.project_import_done))
                    }
                is YarnImportState.Importing,
                YarnImportState.Saving -> Unit
            }
        },
        dismissButton = {
            when (state) {
                YarnImportState.Idle,
                is YarnImportState.AwaitingConfirmation,
                is YarnImportState.Failed ->
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                is YarnImportState.Importing ->
                    TextButton(
                        modifier = Modifier.testTag("yarn-import-cancel"),
                        onClick = onCancel,
                    ) {
                        Text(stringResource(R.string.project_import_cancel))
                    }
                YarnImportState.Saving,
                is YarnImportState.Completed -> Unit
            }
        },
    )
}

@Composable
private fun ImportProgress(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Column {
            Text(text)
            Text(
                text = stringResource(R.string.import_progress_slow_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun yarnImportFailureMessage(reason: ProjectImportFailure): String =
    stringResource(
        when (reason) {
            ProjectImportFailure.InvalidUrl -> R.string.yarn_import_error_invalid_url
            ProjectImportFailure.Offline -> R.string.project_import_error_offline
            ProjectImportFailure.RateLimited -> R.string.project_import_error_rate_limited
            ProjectImportFailure.Blocked -> R.string.import_error_blocked
            ProjectImportFailure.NotFound -> R.string.import_error_not_found
            ProjectImportFailure.Disabled -> R.string.import_error_disabled
            ProjectImportFailure.Server -> R.string.yarn_import_error_server
            ProjectImportFailure.Unknown -> R.string.yarn_import_error_unknown
        }
    )
