package blue.anika.wolle.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) onSaved()
    }
    LaunchedEffect(deleted) {
        if (deleted) onDeleted()
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Edit project" else "New project") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        if (viewModel.isEditing) {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete project")
                            }
                        }
                        IconButton(onClick = viewModel::save) {
                            Icon(Icons.Filled.Check, contentDescription = "Save")
                        }
                    }
                },
            )
        },
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
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.category,
                    onValueChange = { value -> viewModel.updateForm { it.copy(category = value) } },
                    label = { Text("Category") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (categories.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { category ->
                            FilterChip(
                                selected = form.category == category.name,
                                onClick = { viewModel.updateForm { it.copy(category = category.name) } },
                                label = { Text(category.name) },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = form.needles,
                    onValueChange = { value -> viewModel.updateForm { it.copy(needles = value) } },
                    label = { Text("Needles") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.stitchSample,
                    onValueChange = { value -> viewModel.updateForm { it.copy(stitchSample = value) } },
                    label = { Text("Stitch sample") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.otherMaterials,
                    onValueChange = { value -> viewModel.updateForm { it.copy(otherMaterials = value) } },
                    label = { Text("Other materials") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = form.tags,
                    onValueChange = { value -> viewModel.updateForm { it.copy(tags = value) } },
                    label = { Text("Tags (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.link,
                    onValueChange = { value -> viewModel.updateForm { it.copy(link = value) } },
                    label = { Text("Link") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { value -> viewModel.updateForm { it.copy(description = value) } },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            item {
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = { value -> viewModel.updateForm { it.copy(notes = value) } },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            if (yarns.isNotEmpty()) {
                item { Text("Linked yarns", style = MaterialTheme.typography.titleSmall) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        yarns.forEach { yarn ->
                            FilterChip(
                                selected = yarn.id in form.selectedYarnIds,
                                onClick = { viewModel.toggleYarnSelected(yarn.id) },
                                label = { Text(yarn.name) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete project?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}
