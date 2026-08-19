package blue.anika.wolle.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon
import blue.anika.wolle.ui.common.EditorSectionCard
import blue.anika.wolle.ui.common.MdiIcons
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectEditorScreen(
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProjectEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val yarns by viewModel.yarns.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var pickingStepIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val titleImagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach(viewModel::addTitleImage)
        }
    val stepImagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val index = pickingStepIndex
            if (index != null) uris.forEach { uri -> viewModel.addStepImage(index, uri) }
            pickingStepIndex = null
        }
    val attachmentPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris.forEach(viewModel::addAttachment)
        }

    LaunchedEffect(saved) { if (saved) onSaved() }
    LaunchedEffect(deleted) { if (deleted) onDeleted() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isEditing) R.string.project_editor_title_edit
                            else R.string.project_new_label
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        if (viewModel.isEditing) {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                DestructiveDeleteIcon(
                                    contentDescription =
                                        stringResource(R.string.project_editor_delete_description)
                                )
                            }
                        }
                        IconButton(onClick = viewModel::save) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.common_save),
                            )
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EditorSectionCard(
                    title = stringResource(R.string.editor_section_project_details),
                    icon = Icons.Filled.Info,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = form.name,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(name = value) }
                            },
                            label = { Text(stringResource(R.string.project_editor_name_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.category,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(category = value) }
                            },
                            label = { Text(stringResource(R.string.common_field_category)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        if (categories.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                categories.forEach { category ->
                                    FilterChip(
                                        selected = form.category == category.name,
                                        onClick = {
                                            viewModel.updateForm { it.copy(category = category.name) }
                                        },
                                        label = { Text(category.name) },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = form.needles,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(needles = value) }
                            },
                            label = { Text(stringResource(R.string.common_field_needles)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
            item {
                EditorSectionCard(
                    title = stringResource(R.string.editor_section_materials),
                    icon = Icons.Filled.Tune,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = form.stitchSample,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(stitchSample = value) }
                            },
                            label = { Text(stringResource(R.string.common_field_stitch_sample)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.otherMaterials,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(otherMaterials = value) }
                            },
                            label = { Text(stringResource(R.string.common_field_other_materials)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = form.tags,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(tags = value) }
                            },
                            label = { Text(stringResource(R.string.project_editor_tags_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.link,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(link = value) }
                            },
                            label = { Text(stringResource(R.string.project_editor_link_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
            item {
                EditorSectionCard(
                    title = stringResource(R.string.editor_section_content),
                    icon = Icons.Filled.Description,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = form.description,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(description = value) }
                            },
                            label = {
                                Text(stringResource(R.string.project_detail_description_title))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        OutlinedTextField(
                            value = form.notes,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(notes = value) }
                            },
                            label = { Text(stringResource(R.string.project_detail_notes_title)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                }
            }
            if (yarns.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.project_detail_linked_yarns_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        yarns.forEach { yarn ->
                            FilterChip(
                                selected = yarn.id in form.selectedYarnIds,
                                onClick = { viewModel.toggleYarnSelected(yarn.id) },
                                leadingIcon = {
                                    val previewUrl = viewModel.previewUrl(yarn)
                                    if (previewUrl != null) {
                                        AsyncImage(
                                            model = previewUrl,
                                            contentDescription = yarn.name,
                                            contentScale = ContentScale.Crop,
                                            modifier =
                                                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)),
                                        )
                                    } else {
                                        Icon(MdiIcons.Sheep, contentDescription = null)
                                    }
                                },
                                label = { Text(yarn.name) },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.project_editor_steps_label),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            itemsIndexed(
                form.steps,
                key = { index, step -> step.id ?: "new-step-$index" },
            ) { index, step ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.moveStep(index, -1) },
                            enabled = index > 0,
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription =
                                    stringResource(R.string.project_editor_move_step_up),
                            )
                        }
                        IconButton(
                            onClick = { viewModel.moveStep(index, 1) },
                            enabled = index < form.steps.lastIndex,
                        ) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription =
                                    stringResource(R.string.project_editor_move_step_down),
                            )
                        }
                        IconButton(onClick = { viewModel.removeStep(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription =
                                    stringResource(R.string.project_editor_remove_step),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = step.title,
                        onValueChange = { value ->
                            viewModel.updateStep(index) { it.copy(title = value) }
                        },
                        label = { Text(stringResource(R.string.project_editor_step_title_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = step.description,
                        onValueChange = { value ->
                            viewModel.updateStep(index) { it.copy(description = value) }
                        },
                        label = {
                            Text(stringResource(R.string.project_editor_step_description_label))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    TextButton(
                        onClick = {
                            pickingStepIndex = index
                            stepImagePicker.launch("image/*")
                        }
                    ) {
                        Text(stringResource(R.string.project_editor_add_step_image))
                    }
                    step.images.forEachIndexed { imageIndex, image ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                image.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeStepImage(index, imageIndex) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription =
                                        stringResource(R.string.project_editor_remove_image),
                                )
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = viewModel::addStep) {
                    Text(stringResource(R.string.project_editor_add_step))
                }
            }
            item {
                TextButton(onClick = { titleImagePicker.launch("image/*") }) {
                    Text(stringResource(R.string.project_editor_add_title_image))
                }
                form.titleImages.forEachIndexed { index, image ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            image.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.removeTitleImage(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription =
                                    stringResource(R.string.project_editor_remove_image),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.project_editor_attachments_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { attachmentPicker.launch("*/*") }) {
                    Text(stringResource(R.string.project_editor_add_attachment))
                }
                form.attachments.forEachIndexed { index, attachment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            attachment.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.removeAttachment(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription =
                                    stringResource(R.string.project_editor_remove_attachment),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        DestructiveDeleteDialog(
            title = stringResource(R.string.project_editor_delete_dialog_title, form.name),
            text = stringResource(R.string.project_editor_delete_dialog_text),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}
