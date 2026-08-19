package blue.anika.wolle.ui.yarns

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YarnEditorScreen(
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    viewModel: YarnEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            viewModel.addPhotos(uris)
        }

    LaunchedEffect(saved) { if (saved) onSaved() }
    LaunchedEffect(deleted) { if (deleted) onDeleted() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isEditing) R.string.yarn_editor_title_edit
                            else R.string.yarns_list_new_yarn_fab
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
                                        stringResource(R.string.yarn_editor_delete_description)
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
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { value -> viewModel.updateForm { it.copy(name = value) } },
                    label = { Text(stringResource(R.string.project_editor_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                Column {
                    TextButton(onClick = { photoPicker.launch("image/*") }) {
                        Text(stringResource(R.string.yarn_editor_add_photo))
                    }
                    form.photos.forEachIndexed { index, photo ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                photo.fileName,
                                modifier = Modifier.weight(1f),
                                style =
                                    androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                            IconButton(onClick = { viewModel.removePhoto(index) }) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Filled.Delete,
                                    contentDescription =
                                        stringResource(R.string.yarn_editor_remove_photo),
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = form.brand,
                    onValueChange = { value -> viewModel.updateForm { it.copy(brand = value) } },
                    label = { Text(stringResource(R.string.yarn_detail_field_brand)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.colorway,
                    onValueChange = { value -> viewModel.updateForm { it.copy(colorway = value) } },
                    label = { Text(stringResource(R.string.yarn_detail_field_colorway)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.dyeLot,
                    onValueChange = { value -> viewModel.updateForm { it.copy(dyeLot = value) } },
                    label = { Text(stringResource(R.string.yarn_detail_field_dye_lot)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.fiberContent,
                    onValueChange = { value ->
                        viewModel.updateForm { it.copy(fiberContent = value) }
                    },
                    label = { Text(stringResource(R.string.yarn_detail_field_fiber_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.weightCategory,
                    onValueChange = { value ->
                        viewModel.updateForm { it.copy(weightCategory = value) }
                    },
                    label = { Text(stringResource(R.string.yarn_detail_field_weight_category)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.recommendedNeedles,
                    onValueChange = { value ->
                        viewModel.updateForm { it.copy(recommendedNeedles = value) }
                    },
                    label = {
                        Text(stringResource(R.string.yarn_detail_field_recommended_needles))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.weightGrams,
                        onValueChange = { value ->
                            viewModel.updateForm {
                                it.copy(weightGrams = value.filter(Char::isDigit))
                            }
                        },
                        label = { Text(stringResource(R.string.yarn_editor_weight_grams_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = form.lengthMeters,
                        onValueChange = { value ->
                            viewModel.updateForm {
                                it.copy(lengthMeters = value.filter(Char::isDigit))
                            }
                        },
                        label = { Text(stringResource(R.string.yarn_editor_length_meters_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = form.link,
                    onValueChange = { value -> viewModel.updateForm { it.copy(link = value) } },
                    label = { Text(stringResource(R.string.project_editor_link_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { value ->
                        viewModel.updateForm { it.copy(description = value) }
                    },
                    label = { Text(stringResource(R.string.project_detail_description_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            item {
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = { value -> viewModel.updateForm { it.copy(notes = value) } },
                    label = { Text(stringResource(R.string.project_detail_notes_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        }
    }

    if (showDeleteDialog) {
        DestructiveDeleteDialog(
            title = stringResource(R.string.yarn_editor_delete_dialog_title, form.name),
            text = stringResource(R.string.project_editor_delete_dialog_text),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}
