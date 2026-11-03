package blue.anika.wolle.ui.projects

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon
import blue.anika.wolle.ui.common.DiscardChangesDialog
import blue.anika.wolle.ui.common.EditorListRow
import blue.anika.wolle.ui.common.EditorPhoto
import blue.anika.wolle.ui.common.EditorPhotoStrip
import blue.anika.wolle.ui.common.EditorSaveBar
import blue.anika.wolle.ui.common.EditorSectionCard
import blue.anika.wolle.ui.common.FeedbackKind
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.PhotoEditHostProvider
import blue.anika.wolle.ui.common.PhotoReorder
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.SuggestionTextField
import blue.anika.wolle.ui.common.rememberPhotoRemovalAnnouncer
import blue.anika.wolle.ui.common.showFeedback
import java.io.File
import kotlinx.coroutines.launch

/**
 * First LazyColumn position of the steps (after details, photos, attachments, materials,
 * description and the steps header).
 */
private const val FIRST_STEP_ITEM = 6

/**
 * The project form, arranged so related things sit together and the common path needs no scrolling
 * back up: details, photos, attachments, materials (linked yarns, needles, stitch sample),
 * description and notes, then one card per step. Save is pinned to the bottom above the keyboard;
 * leaving with unsaved changes asks first; removing a step can be undone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectEditorScreen(
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProjectEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val reorderAvailable by viewModel.reorderAvailable.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val yarns by viewModel.yarns.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showYarnPicker by rememberSaveable { mutableStateOf(false) }
    val isLoaded by viewModel.isLoaded.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val detailsFocus = remember { FocusRequester() }
    val stitchSampleFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    val stepFocus = remember { FocusRequester() }
    val newStepFocus = remember { FocusRequester() }
    var newStepIndex by remember { mutableStateOf<Int?>(null) }
    val focusedStepId = viewModel.initialFocus?.let(ProjectEditorFocus::stepId)
    var initialFocusApplied by rememberSaveable { mutableStateOf(false) }
    val stepRemovedMessage = stringResource(R.string.project_editor_step_removed)
    val undoLabel = stringResource(R.string.common_undo)
    val announceRemoval = rememberPhotoRemovalAnnouncer(snackbarHostState)

    val leave = { if (isDirty && !saved) showDiscardDialog = true else onBack() }
    BackHandler(enabled = isDirty && !saved && !deleted) { showDiscardDialog = true }

    // Opened from a long-press on one detail section or step: scroll there once the project is
    // loaded and put the cursor in its first field.
    LaunchedEffect(isLoaded) {
        val focus = viewModel.initialFocus
        if (!isLoaded || initialFocusApplied || focus == null) return@LaunchedEffect
        initialFocusApplied = true
        val stepIndex = focusedStepId?.let { id -> form.steps.indexOfFirst { it.id == id } }
        val index = projectEditorItemIndex(focus, stepIndex) ?: return@LaunchedEffect
        listState.scrollToItem(index)
        withFrameNanos {}
        val requester =
            when {
                focusedStepId != null -> stepFocus
                focus == ProjectEditorFocus.STITCH_SAMPLE -> stitchSampleFocus
                focus == ProjectEditorFocus.DESCRIPTION -> descriptionFocus
                focus == ProjectEditorFocus.NOTES -> notesFocus
                focus == ProjectEditorFocus.DETAILS -> detailsFocus
                else -> null
            }
        requester?.let { runCatching { it.requestFocus() } }
    }
    // A freshly added step scrolls into view with the cursor in its title.
    LaunchedEffect(newStepIndex, form.steps.size) {
        val index = newStepIndex ?: return@LaunchedEffect
        if (index > form.steps.lastIndex) return@LaunchedEffect
        listState.animateScrollToItem(FIRST_STEP_ITEM + index)
        withFrameNanos {}
        runCatching { newStepFocus.requestFocus() }
        newStepIndex = null
    }
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
    PhotoEditHostProvider { hostModifier ->
        Scaffold(
            modifier = hostModifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
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
                        IconButton(onClick = leave) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    },
                    actions = {
                        if (viewModel.isEditing) {
                            IconButton(onClick = { showDeleteDialog = true }, enabled = !isSaving) {
                                DestructiveDeleteIcon(
                                    contentDescription =
                                        stringResource(R.string.project_editor_delete_description)
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = { EditorSaveBar(isSaving = isSaving, onSave = viewModel::save) },
        ) { innerPadding ->
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize().padding(innerPadding).testTag("e2e-project-editor-form"),
                state = listState,
                contentPadding =
                    PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 0: what it is - name, category, tags, link.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_project_details),
                        icon = PhosphorIcons.Info,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = form.name,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(name = value) }
                                },
                                label = {
                                    Text(stringResource(R.string.project_editor_name_label))
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .focusRequester(detailsFocus)
                                        .testTag("e2e-project-editor-name"),
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        imeAction = ImeAction.Next,
                                    ),
                            )
                            SuggestionTextField(
                                value = form.category,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(category = value) }
                                },
                                label = stringResource(R.string.common_field_category),
                                suggestions = categories.map { it.name },
                            )
                            OutlinedTextField(
                                value = form.tags,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(tags = value) }
                                },
                                label = {
                                    Text(stringResource(R.string.project_editor_tags_label))
                                },
                                supportingText = {
                                    Text(stringResource(R.string.project_editor_tags_hint))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            )
                            OutlinedTextField(
                                value = form.link,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(link = value) }
                                },
                                label = {
                                    Text(stringResource(R.string.project_editor_link_label))
                                },
                                placeholder = { Text("https://") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next,
                                    ),
                            )
                        }
                    }
                }
                // 1: photos as thumbnails, add tile last.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_gallery),
                        icon = PhosphorIcons.Images,
                    ) {
                        val removeLabel = stringResource(R.string.project_editor_remove_image)
                        EditorPhotoStrip(
                            photos =
                                form.existingTitleImages.map { image ->
                                    EditorPhoto(
                                        key = "existing-${image.id}",
                                        model = viewModel.imageUrl(image.thumbnailUrl),
                                        description = image.altText,
                                        onRemove = {
                                            viewModel
                                                .removeExistingTitleImage(image.id)
                                                ?.let(announceRemoval)
                                        },
                                    )
                                } +
                                    form.titleImages.mapIndexed { index, upload ->
                                        EditorPhoto(
                                            key = "new-${upload.path}",
                                            model = File(upload.path),
                                            description = upload.fileName,
                                            onRemove = {
                                                viewModel
                                                    .removeTitleImage(index)
                                                    ?.let(announceRemoval)
                                            },
                                        )
                                    },
                            addLabel = stringResource(R.string.editor_add_photo_short),
                            removeLabel = removeLabel,
                            onAdd = { titleImagePicker.launch("image/*") },
                            reorder =
                                if (reorderAvailable) {
                                    PhotoReorder(form.existingTitleImages.size) { from, to ->
                                        viewModel.moveTitleImage(from, to)
                                    }
                                } else null,
                        )
                    }
                }
                // 2: pattern PDFs and other files, close to the photos (SNA-81).
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.project_editor_attachments_label),
                        icon = PhosphorIcons.Paperclip,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val removeLabel =
                                stringResource(R.string.project_editor_remove_attachment)
                            form.attachments.forEachIndexed { index, attachment ->
                                EditorListRow(
                                    leading = {
                                        Icon(
                                            Icons.Filled.InsertDriveFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    title = attachment.fileName,
                                    subtitle = null,
                                    removeLabel = removeLabel,
                                    onRemove = { viewModel.removeAttachment(index) },
                                )
                            }
                            OutlinedButton(
                                onClick = { attachmentPicker.launch("*/*") },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(stringResource(R.string.project_editor_add_attachment))
                            }
                        }
                    }
                }
                // 3: what you knit it with.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_materials),
                        icon = PhosphorIcons.Needle,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.project_detail_linked_yarns_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val linked = yarns.filter { it.id in form.selectedYarnIds }
                            val unlinkLabel = stringResource(R.string.project_editor_unlink_yarn)
                            linked.forEach { yarn ->
                                EditorListRow(
                                    leading = {
                                        YarnThumbnail(viewModel.previewUrl(yarn), size = 40)
                                    },
                                    title = yarn.name,
                                    subtitle = yarnSubtitle(yarn),
                                    removeLabel = unlinkLabel,
                                    onRemove = { viewModel.toggleYarnSelected(yarn.id) },
                                )
                            }
                            if (yarns.isEmpty()) {
                                Text(
                                    stringResource(R.string.project_editor_no_yarns_yet),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { showYarnPicker = true },
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .testTag("project-editor-pick-yarns"),
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(
                                        stringResource(
                                            if (linked.isEmpty()) R.string.project_editor_link_yarns
                                            else R.string.project_editor_change_yarns
                                        )
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = form.needles,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(needles = value) }
                                },
                                label = { Text(stringResource(R.string.common_field_needles)) },
                                placeholder = {
                                    Text(stringResource(R.string.project_editor_needles_hint))
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            )
                            // Stitch samples are often several lines (stitches, rows, needle,
                            // washed or not), so the field grows instead of scrolling sideways.
                            OutlinedTextField(
                                value = form.stitchSample,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(stitchSample = value) }
                                },
                                label = {
                                    Text(stringResource(R.string.common_field_stitch_sample))
                                },
                                placeholder = {
                                    Text(stringResource(R.string.project_editor_stitch_sample_hint))
                                },
                                modifier =
                                    Modifier.fillMaxWidth().focusRequester(stitchSampleFocus),
                                minLines = 2,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences
                                    ),
                            )
                            OutlinedTextField(
                                value = form.otherMaterials,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(otherMaterials = value) }
                                },
                                label = {
                                    Text(stringResource(R.string.common_field_other_materials))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences
                                    ),
                            )
                        }
                    }
                }
                // 4: free text.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_content),
                        icon = PhosphorIcons.NotePencil,
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
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .focusRequester(descriptionFocus)
                                        .testTag("e2e-project-editor-description"),
                                minLines = 4,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences
                                    ),
                            )
                            OutlinedTextField(
                                value = form.notes,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(notes = value) }
                                },
                                label = {
                                    Text(stringResource(R.string.project_detail_notes_title))
                                },
                                modifier = Modifier.fillMaxWidth().focusRequester(notesFocus),
                                minLines = 3,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences
                                    ),
                            )
                        }
                    }
                }
                // 5: steps header.
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    ) {
                        Icon(
                            PhosphorIcons.ListNumbers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.editor_section_steps),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                itemsIndexed(form.steps, key = { index, step -> step.id ?: "new-step-$index" }) {
                    index,
                    step ->
                    StepEditorCard(
                        number = index + 1,
                        step = step,
                        canMoveUp = index > 0,
                        canMoveDown = index < form.steps.lastIndex,
                        titleModifier =
                            Modifier.let {
                                    if (step.id != null && step.id == focusedStepId) {
                                        it.focusRequester(stepFocus)
                                    } else it
                                }
                                .let {
                                    if (index == newStepIndex) it.focusRequester(newStepFocus)
                                    else it
                                },
                        imageUrl = viewModel::imageUrl,
                        onTitleChange = { value ->
                            viewModel.updateStep(index) { it.copy(title = value) }
                        },
                        onDescriptionChange = { value ->
                            viewModel.updateStep(index) { it.copy(description = value) }
                        },
                        onMove = { direction -> viewModel.moveStep(index, direction) },
                        onRemove = {
                            viewModel.removeStep(index)
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                val result =
                                    snackbarHostState.showFeedback(
                                        message = stepRemovedMessage,
                                        kind = FeedbackKind.Info,
                                        actionLabel = undoLabel,
                                    )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.insertStep(index, step)
                                }
                            }
                        },
                        onAddImage = {
                            pickingStepIndex = index
                            stepImagePicker.launch("image/*")
                        },
                        reorder =
                            if (reorderAvailable) {
                                PhotoReorder(step.existingImages.size) { from, to ->
                                    viewModel.moveStepImage(index, from, to)
                                }
                            } else null,
                        onRemoveExistingImage = { id ->
                            viewModel.removeExistingStepImage(index, id)?.let(announceRemoval)
                        },
                        onRemoveNewImage = { imageIndex ->
                            viewModel.removeStepImage(index, imageIndex)?.let(announceRemoval)
                        },
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = {
                            viewModel.addStep()
                            newStepIndex = form.steps.size
                        },
                        contentPadding = PaddingValues(vertical = 14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(R.string.project_editor_add_step))
                    }
                }
            }
        }
    }

    if (showYarnPicker) {
        YarnPickerSheet(
            yarns = yarns,
            selected = form.selectedYarnIds,
            previewUrl = viewModel::previewUrl,
            onToggle = viewModel::toggleYarnSelected,
            onDismiss = { showYarnPicker = false },
        )
    }

    if (showDiscardDialog) {
        DiscardChangesDialog(
            onKeepEditing = { showDiscardDialog = false },
            onDiscard = {
                showDiscardDialog = false
                onBack()
            },
        )
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

/** One instruction step: number badge, title, text, photos; reorder and remove in a menu. */
@Composable
private fun StepEditorCard(
    number: Int,
    step: ProjectEditorStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    titleModifier: Modifier,
    imageUrl: (String) -> String?,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onAddImage: () -> Unit,
    reorder: PhotoReorder?,
    onRemoveExistingImage: (Int) -> Unit,
    onRemoveNewImage: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$number", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(
                    stringResource(R.string.project_editor_step_number, number),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription =
                                stringResource(R.string.project_editor_step_actions, number),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.project_editor_move_step_up)) },
                            leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                            enabled = canMoveUp,
                            onClick = {
                                menuOpen = false
                                onMove(-1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.project_editor_move_step_down)) },
                            leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                            enabled = canMoveDown,
                            onClick = {
                                menuOpen = false
                                onMove(1)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.project_editor_remove_step),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    PhosphorIcons.Trash,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(end = 12.dp, top = 6.dp),
            ) {
                OutlinedTextField(
                    value = step.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.project_editor_step_title_label)) },
                    modifier = titleModifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                )
                OutlinedTextField(
                    value = step.description,
                    onValueChange = onDescriptionChange,
                    label = {
                        Text(stringResource(R.string.project_editor_step_description_label))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    keyboardOptions =
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                EditorPhotoStrip(
                    photos =
                        step.existingImages.map { image ->
                            EditorPhoto(
                                key = "existing-${image.id}",
                                model = imageUrl(image.thumbnailUrl),
                                description = image.altText,
                                onRemove = { onRemoveExistingImage(image.id) },
                            )
                        } +
                            step.images.mapIndexed { imageIndex, upload ->
                                EditorPhoto(
                                    key = "new-${upload.path}",
                                    model = File(upload.path),
                                    description = upload.fileName,
                                    onRemove = { onRemoveNewImage(imageIndex) },
                                )
                            },
                    addLabel = stringResource(R.string.editor_add_photo_short),
                    removeLabel = stringResource(R.string.project_editor_remove_image),
                    onAdd = onAddImage,
                    thumbnailSize = 84,
                    reorder = reorder,
                )
            }
        }
    }
}

/** Editor sections a detail long-press can open; steps are addressed by their server ID. */
internal object ProjectEditorFocus {
    const val DETAILS = "details"
    const val ATTACHMENTS = "attachments"
    const val STITCH_SAMPLE = "stitch_sample"
    const val LINKED_YARNS = "linked_yarns"
    const val DESCRIPTION = "description"
    const val NOTES = "notes"

    fun step(id: Int): String = "step:$id"

    fun stepId(focus: String): Int? =
        if (focus.startsWith("step:")) focus.removePrefix("step:").toIntOrNull() else null
}

/**
 * LazyColumn position of a focus target in [ProjectEditorScreen]. Keep in sync with the items
 * there: details 0, photos 1, attachments 2, materials (linked yarns, needles, stitch sample) 3,
 * description and notes 4, steps header 5, then one item per step from [FIRST_STEP_ITEM].
 * [stepIndex] is the step's index in the form, or null/-1 when the step is unknown (the steps
 * header is used then).
 */
internal fun projectEditorItemIndex(focus: String, stepIndex: Int?): Int? =
    when {
        ProjectEditorFocus.stepId(focus) != null ->
            if (stepIndex != null && stepIndex >= 0) FIRST_STEP_ITEM + stepIndex
            else FIRST_STEP_ITEM - 1
        focus == ProjectEditorFocus.DETAILS -> 0
        focus == ProjectEditorFocus.ATTACHMENTS -> 2
        focus == ProjectEditorFocus.STITCH_SAMPLE || focus == ProjectEditorFocus.LINKED_YARNS -> 3
        focus == ProjectEditorFocus.DESCRIPTION || focus == ProjectEditorFocus.NOTES -> 4
        else -> null
    }
