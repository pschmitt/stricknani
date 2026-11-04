package blue.anika.wolle.ui.yarns

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import blue.anika.wolle.ui.common.EditorPhoto
import blue.anika.wolle.ui.common.EditorPhotoStrip
import blue.anika.wolle.ui.common.EditorSaveBar
import blue.anika.wolle.ui.common.EditorSectionCard
import blue.anika.wolle.ui.common.OwnerBanner
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.PhotoEditHostProvider
import blue.anika.wolle.ui.common.PhotoReorder
import blue.anika.wolle.ui.common.StickyNoteField
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.SuggestionTextField
import blue.anika.wolle.ui.common.UrlTextField
import blue.anika.wolle.ui.common.rememberCameraDeniedNotifier
import blue.anika.wolle.ui.common.rememberPhotoCapture
import blue.anika.wolle.ui.common.rememberPhotoRemovalAnnouncer
import java.io.File

/**
 * Common yarn weight names (Craft Yarn Council), offered as suggestions; anything else can still be
 * typed, as on the website.
 */
internal val YARN_WEIGHT_SUGGESTIONS =
    listOf(
        "Lace",
        "Fingering",
        "Sport",
        "DK",
        "Worsted",
        "Aran",
        "Bulky",
        "Super Bulky",
        "Jumbo",
    )

/**
 * The yarn form in the order you read a yarn label: name, brand, colourway and dye lot; photos;
 * then fibre, weight, needles and yardage; then free text. Same behaviour as the project editor:
 * Save pinned to the bottom above the keyboard, and leaving with unsaved changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YarnEditorScreen(
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    viewModel: YarnEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val reorderAvailable by viewModel.reorderAvailable.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()
    val banner by viewModel.ownerBanner.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val isLoaded by viewModel.isLoaded.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val announceRemoval = rememberPhotoRemovalAnnouncer(snackbarHostState)
    val detailsFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    var initialFocusApplied by rememberSaveable { mutableStateOf(false) }

    val leave = { if (isDirty && !saved) showDiscardDialog = true else onBack() }
    BackHandler(enabled = isDirty && !saved && !deleted) { showDiscardDialog = true }

    // Opened from a long-press on a yarn detail section: scroll there once loaded and put the
    // cursor in its first field.
    LaunchedEffect(isLoaded) {
        val focus = viewModel.initialFocus
        if (!isLoaded || initialFocusApplied || focus == null) return@LaunchedEffect
        initialFocusApplied = true
        val index = yarnEditorItemIndex(focus) ?: return@LaunchedEffect
        listState.scrollToItem(index)
        withFrameNanos {}
        val requester =
            when (focus) {
                YarnEditorFocus.DETAILS -> detailsFocus
                YarnEditorFocus.DESCRIPTION -> descriptionFocus
                YarnEditorFocus.NOTES -> notesFocus
                else -> null
            }
        requester?.let { runCatching { it.requestFocus() } }
    }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            viewModel.addPhotos(uris)
        }
    val photoCapture =
        rememberPhotoCapture(
            onPhoto = { uri -> viewModel.addPhotos(listOf(uri)) },
            onPermissionDenied = rememberCameraDeniedNotifier(snackbarHostState),
        )

    LaunchedEffect(saved) { if (saved) onSaved() }
    LaunchedEffect(deleted) { if (deleted) onDeleted() }
    val nextSentences =
        KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
        )
    PhotoEditHostProvider { hostModifier ->
        Scaffold(
            modifier = hostModifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
            topBar = {
                Column {
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
                                            stringResource(R.string.yarn_editor_delete_description)
                                    )
                                }
                            }
                        },
                    )
                    banner?.let { OwnerBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }
            },
            bottomBar = { EditorSaveBar(isSaving = isSaving, onSave = viewModel::save) },
        ) { innerPadding ->
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize().padding(innerPadding).testTag("e2e-yarn-editor-form"),
                state = listState,
                contentPadding =
                    PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 0: what's on the label.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_yarn_details),
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
                                        .testTag("e2e-yarn-editor-name"),
                                singleLine = true,
                                keyboardOptions = nextSentences,
                            )
                            OutlinedTextField(
                                value = form.brand,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(brand = value) }
                                },
                                label = { Text(stringResource(R.string.yarn_detail_field_brand)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = nextSentences,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = form.colorway,
                                    onValueChange = { value ->
                                        viewModel.updateForm { it.copy(colorway = value) }
                                    },
                                    label = {
                                        Text(stringResource(R.string.yarn_detail_field_colorway))
                                    },
                                    modifier = Modifier.weight(1.4f),
                                    singleLine = true,
                                    keyboardOptions = nextSentences,
                                )
                                OutlinedTextField(
                                    value = form.dyeLot,
                                    onValueChange = { value ->
                                        viewModel.updateForm { it.copy(dyeLot = value) }
                                    },
                                    label = {
                                        Text(stringResource(R.string.yarn_detail_field_dye_lot))
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Characters,
                                            imeAction = ImeAction.Next,
                                        ),
                                )
                            }
                        }
                    }
                }
                // 1: photos, right under the name - a yarn is recognised by its colour.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_photos),
                        icon = PhosphorIcons.Images,
                    ) {
                        EditorPhotoStrip(
                            photos =
                                form.existingPhotos.map { photo ->
                                    EditorPhoto(
                                        key = "existing-${photo.id}",
                                        model = viewModel.imageUrl(photo.thumbnailUrl),
                                        description = photo.altText,
                                        onRemove = {
                                            viewModel
                                                .removeExistingPhoto(photo.id)
                                                ?.let(announceRemoval)
                                        },
                                    )
                                } +
                                    form.photos.mapIndexed { index, upload ->
                                        EditorPhoto(
                                            key = "new-${upload.path}",
                                            model = File(upload.path),
                                            description = upload.fileName,
                                            onRemove = {
                                                viewModel.removePhoto(index)?.let(announceRemoval)
                                            },
                                        )
                                    },
                            addLabel = stringResource(R.string.editor_add_photo_short),
                            removeLabel = stringResource(R.string.yarn_editor_remove_photo),
                            onAdd = { photoPicker.launch("image/*") },
                            takePhotoLabel = stringResource(R.string.photo_take),
                            onTakePhoto = photoCapture.launch.takeIf { photoCapture.available },
                            reorder =
                                if (reorderAvailable) {
                                    PhotoReorder(form.existingPhotos.size) { from, to ->
                                        viewModel.moveExistingPhoto(from, to)
                                    }
                                } else null,
                        )
                    }
                }
                // 2: what it's made of and how it knits.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_yarn_specs),
                        icon = PhosphorIcons.SlidersHorizontal,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = form.fiberContent,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(fiberContent = value) }
                                },
                                label = {
                                    Text(stringResource(R.string.yarn_detail_field_fiber_content))
                                },
                                placeholder = {
                                    Text(stringResource(R.string.yarn_editor_fiber_content_hint))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = nextSentences,
                            )
                            SuggestionTextField(
                                value = form.weightCategory,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(weightCategory = value) }
                                },
                                label = stringResource(R.string.yarn_detail_field_weight_category),
                                suggestions = YARN_WEIGHT_SUGGESTIONS,
                            )
                            OutlinedTextField(
                                value = form.recommendedNeedles,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(recommendedNeedles = value) }
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.yarn_detail_field_recommended_needles
                                        )
                                    )
                                },
                                placeholder = {
                                    Text(stringResource(R.string.project_editor_needles_hint))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = form.weightGrams,
                                    onValueChange = { value ->
                                        viewModel.updateForm {
                                            it.copy(weightGrams = value.filter(Char::isDigit))
                                        }
                                    },
                                    label = {
                                        Text(
                                            stringResource(R.string.yarn_editor_weight_grams_label)
                                        )
                                    },
                                    suffix = { Text("g") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Next,
                                        ),
                                )
                                OutlinedTextField(
                                    value = form.lengthMeters,
                                    onValueChange = { value ->
                                        viewModel.updateForm {
                                            it.copy(lengthMeters = value.filter(Char::isDigit))
                                        }
                                    },
                                    label = {
                                        Text(
                                            stringResource(R.string.yarn_editor_length_meters_label)
                                        )
                                    },
                                    suffix = { Text("m") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Next,
                                        ),
                                )
                            }
                        }
                    }
                }
                // 3: free text and the shop link.
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
                                modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocus),
                                minLines = 3,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences
                                    ),
                            )
                            StickyNoteField(
                                value = form.notes,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(notes = value) }
                                },
                                label = stringResource(R.string.project_detail_notes_title),
                                fieldModifier = Modifier.focusRequester(notesFocus),
                                minLines = 3,
                            )
                            UrlTextField(
                                value = form.link,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(link = value) }
                                },
                                label = stringResource(R.string.project_editor_link_label),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
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

/** Yarn editor sections a detail long-press can open. */
internal object YarnEditorFocus {
    const val DETAILS = "details"
    const val DESCRIPTION = "description"
    const val NOTES = "notes"
}

/**
 * LazyColumn position of a focus target in [YarnEditorScreen]. Keep in sync with the items there:
 * details 0, photos 1, specifications 2, description/notes/link 3.
 */
internal fun yarnEditorItemIndex(focus: String): Int? =
    when (focus) {
        YarnEditorFocus.DETAILS -> 0
        YarnEditorFocus.DESCRIPTION,
        YarnEditorFocus.NOTES -> 3
        else -> null
    }
