package blue.anika.wolle.ui.yarns

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
                title = { Text(if (viewModel.isEditing) "Edit yarn" else "New yarn") },
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
                                Icon(Icons.Filled.Delete, contentDescription = "Delete yarn")
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
                    value = form.brand,
                    onValueChange = { value -> viewModel.updateForm { it.copy(brand = value) } },
                    label = { Text("Brand") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.colorway,
                    onValueChange = { value -> viewModel.updateForm { it.copy(colorway = value) } },
                    label = { Text("Colorway") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.dyeLot,
                    onValueChange = { value -> viewModel.updateForm { it.copy(dyeLot = value) } },
                    label = { Text("Dye lot") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = form.fiberContent,
                    onValueChange = { value -> viewModel.updateForm { it.copy(fiberContent = value) } },
                    label = { Text("Fiber content") },
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
                    label = { Text("Weight category") },
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
                    label = { Text("Recommended needles") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.weightGrams,
                        onValueChange = { value ->
                            viewModel.updateForm { it.copy(weightGrams = value.filter(Char::isDigit)) }
                        },
                        label = { Text("Weight (g)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = form.lengthMeters,
                        onValueChange = { value ->
                            viewModel.updateForm { it.copy(lengthMeters = value.filter(Char::isDigit)) }
                        },
                        label = { Text("Length (m)") },
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
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete yarn?") },
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
