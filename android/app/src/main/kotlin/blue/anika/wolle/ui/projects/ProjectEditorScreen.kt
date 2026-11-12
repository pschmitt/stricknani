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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.ui.common.CategorySelectField
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon
import blue.anika.wolle.ui.common.DiscardChangesDialog
import blue.anika.wolle.ui.common.EditorListRow
import blue.anika.wolle.ui.common.EditorPhoto
import blue.anika.wolle.ui.common.EditorPhotoStrip
import blue.anika.wolle.ui.common.EditorSaveBar
import blue.anika.wolle.ui.common.EditorSectionCard
import blue.anika.wolle.ui.common.FeedbackKind
import blue.anika.wolle.ui.common.FieldLabel
import blue.anika.wolle.ui.common.ImportAssetRow
import blue.anika.wolle.ui.common.ImportImagePicker
import blue.anika.wolle.ui.common.MarkdownTextField
import blue.anika.wolle.ui.common.NewBanner
import blue.anika.wolle.ui.common.NewBannerKind
import blue.anika.wolle.ui.common.OwnerBanner
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.PhotoEditHostProvider
import blue.anika.wolle.ui.common.PhotoEditTarget
import blue.anika.wolle.ui.common.PhotoEditorDialog
import blue.anika.wolle.ui.common.PhotoReorder
import blue.anika.wolle.ui.common.ProjectStatusSelectField
import blue.anika.wolle.ui.common.StagedPhoto
import blue.anika.wolle.ui.common.StagedRemovalList
import blue.anika.wolle.ui.common.StickyNoteField
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.UrlTextField
import blue.anika.wolle.ui.common.photoAutoScroll
import blue.anika.wolle.ui.common.rememberCameraDeniedNotifier
import blue.anika.wolle.ui.common.rememberPhotoCapture
import blue.anika.wolle.ui.common.rememberPhotoRemovalAnnouncer
import blue.anika.wolle.ui.common.showFeedback
import blue.anika.wolle.ui.common.stagedPhotoLabel
import blue.anika.wolle.ui.common.urlHostOrNull
import blue.anika.wolle.ui.imports.EditorImportState
import blue.anika.wolle.ui.imports.ImportLinkCard
import blue.anika.wolle.ui.imports.readPickedImportFile
import blue.anika.wolle.ui.trash.UnsavedStatus
import blue.anika.wolle.ui.trash.saveBarState
import blue.anika.wolle.ui.trash.stagedRemovals
import blue.anika.wolle.ui.trash.unsavedStatus
import java.io.File
import kotlinx.coroutines.launch

/**
 * First LazyColumn position of the steps (after details, photos, attachments, materials,
 * description and the steps header).
 */
private const val FIRST_STEP_ITEM = 7

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
    val knownTags by viewModel.knownTags.collectAsStateWithLifecycle()
    val yarns by viewModel.yarns.collectAsStateWithLifecycle()
    val banner by viewModel.ownerBanner.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val canDelete by viewModel.canDelete.collectAsStateWithLifecycle()
    val canEdit by viewModel.canEdit.collectAsStateWithLifecycle()
    // A reader (viewer, derived yarn) has no editor: a stale link or back stack leaves at once.
    LaunchedEffect(canEdit) { if (viewModel.isEditing && !canEdit) onBack() }
    val picksFromOwner by viewModel.picksFromOwner.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()
    val initialForm by viewModel.initialForm.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val importNotice by viewModel.importNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val amountLabel = stringResource(R.string.yarn_detail_field_amount)
    // The import is ready: its result fills this form's own fields.
    LaunchedEffect(importState) {
        val ready = importState as? EditorImportState.Ready ?: return@LaunchedEffect
        viewModel.applyImport(ready.draft) { preview ->
            projectImportNotes(context, preview.request.notes, preview.details)
        }
    }
    // Not saved across recreation: a delete prompt must never reappear by itself.
    var deleteRequestId by remember { mutableStateOf<Int?>(null) }
    // A new project's link: offer to import the pattern instead, once per link (as on the web).
    var importOfferUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var declinedImportUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val offerImport = { value: String ->
        val url = value.trim()
        if (
            urlHostOrNull(url) != null && url != declinedImportUrl && viewModel.offersLinkImport()
        ) {
            importOfferUrl = url
        }
    }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showYarnPicker by rememberSaveable { mutableStateOf(false) }
    val isLoaded by viewModel.isLoaded.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val nameFocus = remember { FocusRequester() }
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
    var photoEdit by remember { mutableStateOf<Triple<PhotoEditTarget, String, String>?>(null) }
    val editImage: (ImageDto) -> Unit = { image ->
        val projectId = viewModel.projectId
        val original = viewModel.imageUrl(image.url)
        if (projectId != null && original != null) {
            photoEdit =
                Triple(PhotoEditTarget.ProjectImage(projectId, image.id), original, image.altText)
        }
    }
    // A photo uploaded just now opens its editor at once.
    val photoToEdit by viewModel.photoToEdit.collectAsStateWithLifecycle()
    LaunchedEffect(photoToEdit) {
        photoToEdit?.let {
            editImage(it)
            viewModel.photoEditShown()
        }
    }
    photoEdit?.let { (target, url, altText) ->
        PhotoEditorDialog(
            target = target,
            imageUrl = url,
            altText = altText,
            onDismiss = { photoEdit = null },
            onSaved = { saved ->
                (target as? PhotoEditTarget.ProjectImage)?.let {
                    viewModel.imageEdited(
                        it.imageId,
                        saved.url,
                        saved.thumbnailUrl,
                        saved.altText,
                    )
                }
            },
        )
    }

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
    val titleCapture =
        rememberPhotoCapture(
            onPhoto = viewModel::addTitleImage,
            onPermissionDenied = rememberCameraDeniedNotifier(snackbarHostState),
        )
    val stepCapture =
        rememberPhotoCapture(
            onPhoto = { uri ->
                val index = pickingStepIndex
                if (index != null) viewModel.addStepImage(index, uri)
                pickingStepIndex = null
            },
            onPermissionDenied = rememberCameraDeniedNotifier(snackbarHostState),
        )
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
                Column {
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
                            if (viewModel.isEditing && canDelete) {
                                IconButton(
                                    onClick = { deleteRequestId = viewModel.projectId },
                                    enabled = !isSaving,
                                ) {
                                    DestructiveDeleteIcon(
                                        contentDescription =
                                            stringResource(
                                                R.string.project_editor_delete_description
                                            )
                                    )
                                }
                            }
                        },
                    )
                    banner?.let {
                        OwnerBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            },
            bottomBar = {
                EditorSaveBar(
                    isSaving = isSaving,
                    onSave = { viewModel.save(amountLabel) },
                    allSaved = saveBarState(viewModel.isEditing, isDirty, isSaving).allSaved,
                    statusText =
                        when (unsavedStatus(isDirty, isSaving, form.removedImageIds.size)) {
                            UnsavedStatus.Plain -> stringResource(R.string.editor_unsaved_changes)
                            UnsavedStatus.WithRemovals ->
                                stringResource(R.string.editor_unsaved_changes_removals)
                            null -> null
                        },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize()
                        .padding(innerPadding)
                        .testTag("e2e-project-editor-form")
                        .photoAutoScroll(listState),
                state = listState,
                contentPadding =
                    PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 0: the import card (new projects) and the name, at the top like the title of the
                // project page.
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!viewModel.isEditing) {
                            NewBanner(
                                kind = NewBannerKind.Project,
                                title = stringResource(R.string.new_banner_project_title),
                                subtitle = stringResource(R.string.new_banner_project_subtitle),
                            )
                        }
                        if (
                            !viewModel.isEditing &&
                                (viewModel.offersImport() ||
                                    importState !is EditorImportState.Idle ||
                                    importNotice != null)
                        ) {
                            ImportLinkCard(
                                yarn = false,
                                state = importState,
                                notice = importNotice,
                                ai = viewModel.aiImports(),
                                onStart = viewModel::startImport,
                                initialUrl = viewModel.sharedLink.orEmpty(),
                                allowLink = viewModel.allowsLinkImport(),
                                onStartText = viewModel::startTextImport,
                                onStartFile = { picked ->
                                    scope.launch {
                                        viewModel.startFileImport(
                                            readPickedImportFile(context.contentResolver, picked)
                                        )
                                    }
                                },
                            )
                        }
                        OutlinedTextField(
                            value = form.name,
                            onValueChange = { value ->
                                viewModel.updateForm { it.copy(name = value) }
                            },
                            label = {
                                FieldLabel(
                                    stringResource(R.string.project_editor_name_label),
                                    PhosphorIcons.Hoodie,
                                )
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .focusRequester(nameFocus)
                                    .testTag("e2e-project-editor-name"),
                            singleLine = true,
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Next,
                                ),
                        )
                    }
                }
                // 1: photos (add tile last), first like the gallery on the project page.
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
                                        onEdit = { editImage(image) },
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
                            editLabel = stringResource(R.string.photo_edit_title),
                            onAdd = { titleImagePicker.launch("image/*") },
                            takePhotoLabel = stringResource(R.string.photo_take),
                            onTakePhoto = titleCapture.launch.takeIf { titleCapture.available },
                            reorder =
                                if (reorderAvailable) {
                                    PhotoReorder(form.existingTitleImages.size) { from, to ->
                                        viewModel.moveTitleImage(from, to)
                                    }
                                } else null,
                        )
                        StagedRemovalList(
                            photos =
                                stagedRemovals(
                                        initialForm.existingTitleImages,
                                        form.removedImageIds,
                                    ) {
                                        it.id
                                    }
                                    .map { image ->
                                        StagedPhoto(
                                            key = "title-${image.id}",
                                            model = viewModel.imageUrl(image.thumbnailUrl),
                                            label =
                                                stagedPhotoLabel(
                                                    image.altText,
                                                    initialForm.existingTitleImages.indexOf(image) +
                                                        1,
                                                ),
                                            onUndo = { viewModel.restoreTitleImage(image.id) },
                                        )
                                    },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        // Pictures the import found: tick the ones to keep.
                        if (form.importImageUrls.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.import_pick_photos),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ImportImagePicker(
                                    urls = form.importImageUrls,
                                    selected = form.selectedImportImages,
                                    onToggle = viewModel::toggleImportImage,
                                    thumbnailUrl = viewModel::importThumbnailUrl,
                                )
                            }
                        }
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
                            // Diagrams and PDFs the import found: tick the ones to keep.
                            if (form.importAssets.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.import_pick_assets),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                form.importAssets.forEach { asset ->
                                    ImportAssetRow(
                                        asset = asset,
                                        selected = asset.url in form.selectedImportAssets,
                                        onToggle = { viewModel.toggleImportAsset(asset.url) },
                                    )
                                }
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
                // 3: the details card: category, needles, stitch sample, tags, link.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_project_details),
                        icon = PhosphorIcons.Info,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProjectStatusSelectField(
                                value = form.status,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(status = value) }
                                },
                                label = stringResource(R.string.project_editor_status_label),
                                labelIcon = Icons.Outlined.Flag,
                            )
                            CategorySelectField(
                                value = form.category,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(category = value) }
                                },
                                label = stringResource(R.string.common_field_category),
                                labelIcon = PhosphorIcons.FolderSimple,
                                categories = categories.map { it.name },
                                modifier = Modifier.focusRequester(detailsFocus),
                            )
                            OutlinedTextField(
                                value = form.needles,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(needles = value) }
                                },
                                label = {
                                    FieldLabel(
                                        stringResource(R.string.common_field_needles),
                                        PhosphorIcons.Needle,
                                    )
                                },
                                placeholder = {
                                    Text(stringResource(R.string.project_editor_needles_hint))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            )
                            // Stitch samples are often several lines (stitches, rows, needle,
                            // washed or not): the shared Markdown editor, one line break per line.
                            MarkdownTextField(
                                value = form.stitchSample,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(stitchSample = value) }
                                },
                                label = stringResource(R.string.common_field_stitch_sample),
                                labelIcon = PhosphorIcons.Ruler,
                                fieldModifier = Modifier.focusRequester(stitchSampleFocus),
                                minLines = 2,
                                keepLineBreaks = true,
                            )
                            TagChipField(
                                tags = form.tags,
                                text = form.tagInput,
                                suggestions =
                                    remember(knownTags, form.tagInput, form.tags) {
                                        suggestTags(knownTags, form.tagInput, form.tags)
                                    },
                                onPickSuggestion = { tag ->
                                    viewModel.updateForm { it.withPickedTag(tag) }
                                },
                                onTextChange = { value, commitAll ->
                                    viewModel.updateForm { it.withTagText(value, commitAll) }
                                },
                                onRemove = { index ->
                                    viewModel.updateForm { it.withoutTag(index) }
                                },
                                onBackspaceInEmptyField = {
                                    viewModel.updateForm { it.withoutLastTagIfInputEmpty() }
                                },
                            )
                            UrlTextField(
                                value = form.link,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(link = value) }
                                },
                                label = stringResource(R.string.project_editor_link_label),
                                labelIcon = PhosphorIcons.Link,
                                modifier = Modifier.fillMaxWidth(),
                                onFocusLost = offerImport,
                            )
                        }
                    }
                }
                // 4: what you knit it with: linked yarns, then other materials.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_materials),
                        icon = PhosphorIcons.Needle,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val linked = yarns.filter { it.id in form.selectedYarnIds }
                            linked.forEach { yarn ->
                                EditorListRow(
                                    leading = {
                                        YarnThumbnail(viewModel.previewUrl(yarn), size = 40)
                                    },
                                    title = yarn.name,
                                    subtitle = yarnSubtitle(yarn),
                                    removeLabel =
                                        stringResource(
                                            R.string.project_editor_unlink_yarn,
                                            yarn.name,
                                        ),
                                    onRemove = { viewModel.toggleYarnSelected(yarn.id) },
                                )
                            }
                            if (linked.isEmpty()) {
                                Text(
                                    stringResource(R.string.project_editor_no_yarns_yet),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (yarns.isNotEmpty()) {
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
                                    Text(stringResource(R.string.project_editor_add_yarn))
                                }
                            }
                            // The yarns the pattern names: from the stash, new, or left out.
                            if (form.importYarns.isNotEmpty()) {
                                ProjectImportYarns(
                                    review = ProjectImportReview(form.importYarns),
                                    onReview = { viewModel.setImportYarns(it.yarns) },
                                    stash = yarns,
                                    previewUrl = viewModel::previewUrl,
                                    showExtras = false,
                                )
                            }
                            // Other materials go under the linked yarns, as on the project page.
                            MarkdownTextField(
                                value = form.otherMaterials,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(otherMaterials = value) }
                                },
                                label = stringResource(R.string.common_field_other_materials),
                                labelIcon = PhosphorIcons.Scissors,
                                modifier = Modifier.padding(top = 4.dp),
                                minLines = 2,
                                keepLineBreaks = true,
                            )
                        }
                    }
                }
                // 5: free text.
                item {
                    EditorSectionCard(
                        title = stringResource(R.string.editor_section_pattern),
                        icon = PhosphorIcons.NotePencil,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MarkdownTextField(
                                value = form.description,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(description = value) }
                                },
                                label = stringResource(R.string.project_detail_pattern_notes_title),
                                labelIcon = PhosphorIcons.TextAlignLeft,
                                fieldModifier =
                                    Modifier.focusRequester(descriptionFocus)
                                        .testTag("e2e-project-editor-description"),
                                minLines = 4,
                            )
                            StickyNoteField(
                                value = form.notes,
                                onValueChange = { value ->
                                    viewModel.updateForm { it.copy(notes = value) }
                                },
                                label = stringResource(R.string.project_detail_notes_title),
                                labelIcon = PhosphorIcons.NotePencil,
                                fieldModifier = Modifier.focusRequester(notesFocus),
                                minLines = 3,
                            )
                        }
                    }
                }
                // 6: steps header.
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
                        importImageUrl = viewModel::importThumbnailUrl,
                        onRemoveImportImage = { url ->
                            viewModel.removeStepImportImage(index, url)
                        },
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
                        onTakePhoto =
                            if (stepCapture.available) {
                                {
                                    pickingStepIndex = index
                                    stepCapture.launch()
                                }
                            } else null,
                        reorder =
                            if (reorderAvailable) {
                                PhotoReorder(step.existingImages.size, firstIsCover = false) {
                                    from,
                                    to ->
                                    viewModel.moveStepImage(index, from, to)
                                }
                            } else null,
                        onEditExistingImage = editImage,
                        onRemoveExistingImage = { id ->
                            viewModel.removeExistingStepImage(index, id)?.let(announceRemoval)
                        },
                        onRemoveNewImage = { imageIndex ->
                            viewModel.removeStepImage(index, imageIndex)?.let(announceRemoval)
                        },
                        stagedImages =
                            run {
                                val initialImages =
                                    initialForm.steps
                                        .firstOrNull { it.id != null && it.id == step.id }
                                        ?.existingImages
                                        .orEmpty()
                                stagedRemovals(initialImages, form.removedImageIds) { it.id }
                                    .map { image ->
                                        StagedPhoto(
                                            key = "step-${image.id}",
                                            model = viewModel.imageUrl(image.thumbnailUrl),
                                            label =
                                                stagedPhotoLabel(
                                                    image.altText,
                                                    initialImages.indexOf(image) + 1,
                                                ),
                                            onUndo = {
                                                viewModel.restoreStepImage(index, image.id)
                                            },
                                        )
                                    }
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
            ownerNote =
                if (picksFromOwner) {
                    if (candidates.offline) stringResource(R.string.yarn_picker_owner_offline)
                    else stringResource(R.string.yarn_picker_owner_yarns)
                } else null,
            onQueryChange = if (picksFromOwner) viewModel::searchYarnCandidates else null,
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

    importOfferUrl?.let { url ->
        AlertDialog(
            onDismissRequest = {
                declinedImportUrl = url
                importOfferUrl = null
            },
            title = { Text(stringResource(R.string.project_editor_import_offer_title)) },
            text = {
                Text(
                    stringResource(
                        if (viewModel.aiImports()) R.string.project_editor_import_offer_text_ai
                        else R.string.project_editor_import_offer_text
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importOfferUrl = null
                        viewModel.startImport(url)
                    },
                    modifier = Modifier.testTag("project-editor-import-offer-accept"),
                ) {
                    Text(stringResource(R.string.project_editor_import_offer_accept))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        declinedImportUrl = url
                        importOfferUrl = null
                    }
                ) {
                    Text(stringResource(R.string.project_editor_import_offer_decline))
                }
            },
        )
    }

    if (deleteRequestId != null) {
        DestructiveDeleteDialog(
            title = stringResource(R.string.project_editor_delete_dialog_title, form.name),
            text = stringResource(R.string.project_editor_delete_dialog_text),
            onConfirm = {
                val requested = deleteRequestId
                deleteRequestId = null
                if (requested != null) viewModel.delete(requested)
            },
            onDismiss = { deleteRequestId = null },
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
    importImageUrl: (String) -> String?,
    onRemoveImportImage: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onAddImage: () -> Unit,
    onTakePhoto: (() -> Unit)?,
    reorder: PhotoReorder?,
    onEditExistingImage: (ImageDto) -> Unit,
    onRemoveExistingImage: (Int) -> Unit,
    onRemoveNewImage: (Int) -> Unit,
    stagedImages: List<StagedPhoto>,
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
                    label = {
                        FieldLabel(
                            stringResource(R.string.project_editor_step_title_label),
                            PhosphorIcons.ListNumbers,
                        )
                    },
                    modifier = titleModifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                )
                MarkdownTextField(
                    value = step.description,
                    onValueChange = onDescriptionChange,
                    label = stringResource(R.string.project_editor_step_description_label),
                    labelIcon = PhosphorIcons.TextAlignLeft,
                    minLines = 3,
                )
                EditorPhotoStrip(
                    photos =
                        step.existingImages.map { image ->
                            EditorPhoto(
                                key = "existing-${image.id}",
                                model = imageUrl(image.thumbnailUrl),
                                description = image.altText,
                                onRemove = { onRemoveExistingImage(image.id) },
                                onEdit = { onEditExistingImage(image) },
                            )
                        } +
                            step.images.mapIndexed { imageIndex, upload ->
                                EditorPhoto(
                                    key = "new-${upload.path}",
                                    model = File(upload.path),
                                    description = upload.fileName,
                                    onRemove = { onRemoveNewImage(imageIndex) },
                                )
                            } +
                            step.importImageUrls.map { url ->
                                EditorPhoto(
                                    key = "import-$url",
                                    model = importImageUrl(url),
                                    description = url.substringAfterLast('/'),
                                    onRemove = { onRemoveImportImage(url) },
                                )
                            },
                    addLabel = stringResource(R.string.editor_add_photo_short),
                    removeLabel = stringResource(R.string.project_editor_remove_image),
                    editLabel = stringResource(R.string.photo_edit_title),
                    onAdd = onAddImage,
                    takePhotoLabel = stringResource(R.string.photo_take),
                    onTakePhoto = onTakePhoto,
                    thumbnailSize = 84,
                    reorder = reorder,
                )
                StagedRemovalList(stagedImages)
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
 * there, which follow the project page: name 0, photos 1, attachments 2, details (category,
 * needles, stitch sample, tags, link) 3, materials (linked yarns, other materials) 4, description
 * and notes 5, steps header 6, then one item per step from [FIRST_STEP_ITEM]. [stepIndex] is the
 * step's index in the form, or null/-1 when the step is unknown (the steps header is used then).
 */
internal fun projectEditorItemIndex(focus: String, stepIndex: Int?): Int? =
    when {
        ProjectEditorFocus.stepId(focus) != null ->
            if (stepIndex != null && stepIndex >= 0) FIRST_STEP_ITEM + stepIndex
            else FIRST_STEP_ITEM - 1
        focus == ProjectEditorFocus.ATTACHMENTS -> 2
        focus == ProjectEditorFocus.DETAILS || focus == ProjectEditorFocus.STITCH_SAMPLE -> 3
        focus == ProjectEditorFocus.LINKED_YARNS -> 4
        focus == ProjectEditorFocus.DESCRIPTION || focus == ProjectEditorFocus.NOTES -> 5
        else -> null
    }
