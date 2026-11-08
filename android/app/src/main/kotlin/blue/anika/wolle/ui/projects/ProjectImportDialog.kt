package blue.anika.wolle.ui.projects

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ImportAvailability
import blue.anika.wolle.data.api.dto.ImportAssetDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.repository.ImportDetailKind
import blue.anika.wolle.ui.common.ImportAvailabilityNotes
import blue.anika.wolle.ui.common.ImportImagePicker
import blue.anika.wolle.ui.common.StickyNoteField

@Composable
fun ProjectImportDialog(
    visible: Boolean,
    initialUrl: String = "",
    state: ProjectImportState,
    onStart: (String) -> Unit,
    onConfirm: (List<String>, ProjectWriteRequest, List<String>) -> Unit,
    onCancel: () -> Unit,
    /** Hides the dialog while the import keeps running in the background. */
    onHide: () -> Unit = onCancel,
    onRetry: () -> Unit,
    availability: ImportAvailability = ImportAvailability(),
    thumbnailUrl: (String) -> String? = { null },
    stashYarns: List<YarnEntity> = emptyList(),
    stashYarnPreviewUrl: (YarnEntity) -> String? = { null },
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    val context = LocalContext.current
    val amountLabel = stringResource(R.string.yarn_detail_field_amount)
    // One review per preview: a decision per yarn, extra stash yarns and the single notes text,
    // which starts with the extracted details that have no field of their own.
    val awaiting = state as? ProjectImportState.AwaitingConfirmation
    var review by
        remember(awaiting?.preview) {
            mutableStateOf(
                awaiting?.preview?.let {
                    ProjectImportReview.from(
                        it,
                        projectImportNotes(context, it.request.notes, it.details).orEmpty(),
                    )
                }
            )
        }
    // The pictures that start out selected come from the preview; the user can change them.
    val preselected =
        (state as? ProjectImportState.AwaitingConfirmation)?.preview?.request?.imageUrls
    var selectedImages by remember(state) { mutableStateOf(preselected.orEmpty().toSet()) }
    // Diagrams and PDFs all start out selected.
    var selectedAssets by
        remember(state) {
            mutableStateOf(
                (state as? ProjectImportState.AwaitingConfirmation)
                    ?.preview
                    ?.assets
                    .orEmpty()
                    .map { it.url }
                    .toSet()
            )
        }

    if (!visible) {
        return
    }

    AlertDialog(
        modifier = Modifier.testTag("project-import-dialog"),
        // While importing, leaving the dialog keeps the import running; only Cancel stops it.
        onDismissRequest = if (state is ProjectImportState.Importing) onHide else onCancel,
        title = {
            Text(
                when (state) {
                    is ProjectImportState.Importing,
                    ProjectImportState.Saving -> stringResource(R.string.project_import_progress)
                    is ProjectImportState.AwaitingConfirmation ->
                        stringResource(R.string.project_import_confirmation_title)
                    is ProjectImportState.Completed ->
                        stringResource(R.string.project_import_success_title)
                    is ProjectImportState.Failed ->
                        stringResource(R.string.project_import_error_title)
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
                    ProjectImportState.Idle -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            enabled = availability.url,
                            modifier = Modifier.testTag("project-import-url"),
                            label = { Text(stringResource(R.string.project_import_url_label)) },
                            placeholder = {
                                Text(stringResource(R.string.project_import_url_placeholder))
                            },
                            singleLine = true,
                        )
                        ImportAvailabilityNotes(availability)
                    }
                    is ProjectImportState.Importing ->
                        ImportProgressContent(
                            text = stringResource(R.string.project_import_progress_description),
                            backgroundHint = true,
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
                        review?.let { current ->
                            ProjectImportYarns(
                                review = current,
                                onReview = { review = it },
                                stash = stashYarns,
                                previewUrl = stashYarnPreviewUrl,
                            )
                            StickyNoteField(
                                value = current.notes,
                                onValueChange = { review = current.withNotes(it) },
                                label = stringResource(R.string.project_detail_notes_title),
                                modifier = Modifier.testTag("project-import-notes"),
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
                                onToggle = { url ->
                                    selectedImages =
                                        if (url in selectedImages) selectedImages - url
                                        else selectedImages + url
                                },
                                thumbnailUrl = thumbnailUrl,
                                previews = availability.url,
                            )
                        }
                        if (state.preview.assets.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.import_pick_assets),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.preview.assets.forEach { asset ->
                                ImportAssetRow(
                                    asset = asset,
                                    selected = asset.url in selectedAssets,
                                    onToggle = {
                                        selectedAssets =
                                            if (asset.url in selectedAssets)
                                                selectedAssets - asset.url
                                            else selectedAssets + asset.url
                                    },
                                )
                            }
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
                        enabled = availability.url,
                        onClick = { onStart(url) },
                    ) {
                        Text(stringResource(R.string.project_import_start))
                    }
                is ProjectImportState.AwaitingConfirmation ->
                    TextButton(
                        modifier = Modifier.testTag("project-import-confirm"),
                        onClick = {
                            val order =
                                (state as ProjectImportState.AwaitingConfirmation).preview.imageUrls
                            val reviewed =
                                review?.applyTo(state.preview.request, amountLabel)
                                    ?: state.preview.request
                            onConfirm(
                                order.filter { it in selectedImages },
                                reviewed,
                                state.preview.assets.map { it.url }.filter { it in selectedAssets },
                            )
                        },
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
                is ProjectImportState.Importing ->
                    TextButton(
                        modifier = Modifier.testTag("project-import-background"),
                        onClick = onHide,
                    ) {
                        Text(stringResource(R.string.import_continue_in_background))
                    }
                ProjectImportState.Saving -> Unit
            }
        },
        dismissButton = {
            when (state) {
                ProjectImportState.Idle,
                is ProjectImportState.AwaitingConfirmation,
                is ProjectImportState.Failed ->
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
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

/** One diagram or PDF of the imported page with its kind, label and a checkbox. */
@Composable
private fun ImportAssetRow(asset: ImportAssetDto, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("import-asset"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column {
            Text(
                text =
                    stringResource(
                        if (asset.isPdf) R.string.import_asset_kind_pdf
                        else R.string.import_asset_kind_diagram
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = asset.label?.takeIf { it.isNotBlank() } ?: asset.url.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ImportProgressContent(
    text: String,
    modifier: Modifier = Modifier,
    backgroundHint: Boolean = false,
) {
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
            if (backgroundHint) {
                Text(
                    text = stringResource(R.string.import_background_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun importFailureMessage(reason: ProjectImportFailure): String =
    stringResource(
        when (reason) {
            ProjectImportFailure.InvalidUrl -> R.string.project_import_error_invalid_url
            ProjectImportFailure.Offline -> R.string.project_import_error_offline
            ProjectImportFailure.RateLimited -> R.string.project_import_error_rate_limited
            ProjectImportFailure.Blocked -> R.string.import_error_blocked
            ProjectImportFailure.NotFound -> R.string.import_error_not_found
            ProjectImportFailure.Disabled -> R.string.import_error_disabled
            ProjectImportFailure.Server -> R.string.project_import_error_server
            ProjectImportFailure.Unknown -> R.string.project_import_error_unknown
        }
    )

internal fun importDetailLabel(kind: ImportDetailKind): Int =
    when (kind) {
        ImportDetailKind.Designer -> R.string.import_detail_designer
        ImportDetailKind.Sizes -> R.string.import_detail_sizes
        ImportDetailKind.Difficulty -> R.string.import_detail_difficulty
    }

/** The explanation for a server warning code, or null for codes the user does not need. */
internal fun importWarningRes(code: String): Int? =
    when (code) {
        "looks_like_yarn_page" -> R.string.import_warning_looks_like_yarn
        "looks_like_pattern_page",
        "not_a_yarn_page" -> R.string.import_warning_looks_like_pattern
        "little_page_text" -> R.string.import_warning_little_text
        "ai_browsed" -> R.string.import_warning_ai_browsed
        "yarn_enrichment_failed" -> R.string.import_warning_yarn_enrichment
        else -> null
    }
