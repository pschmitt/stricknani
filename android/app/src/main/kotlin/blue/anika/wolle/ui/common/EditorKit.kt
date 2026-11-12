package blue.anika.wolle.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.canMoveToFirst
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Building blocks shared by the project and yarn editors, so both behave the same:
 *  - the primary action (Save) lives at the bottom, in thumb reach, and rides above the keyboard;
 *  - photos are a strip of thumbnails you can see, not a list of file names;
 *  - free-text fields with common values offer suggestions instead of a wall of chips;
 *  - leaving with unsaved changes asks first.
 */

/**
 * A photo taken out of an editor form. Nothing is final until the form is saved, so [undo] simply
 * puts it back; [finalize] releases what the removal kept alive (a copied upload file) once the
 * undo chance has passed.
 */
class PhotoRemoval(
    val undo: () -> Unit,
    val finalize: () -> Unit = {},
    /** True for a photo already on the server: it moves to the trash when the form is saved. */
    val toTrash: Boolean = false,
)

/**
 * Returns a function that shows the "Photo removed" snackbar with Undo for a [PhotoRemoval] on
 * [host]. A newer removal replaces the snackbar of an older one, which then becomes final.
 */
@Composable
fun rememberPhotoRemovalAnnouncer(host: SnackbarHostState): (PhotoRemoval) -> Unit {
    val scope = rememberCoroutineScope()
    val message = stringResource(R.string.editor_photo_removed)
    val trashMessage = stringResource(R.string.editor_photo_removed_to_trash)
    val undoLabel = stringResource(R.string.common_undo)
    return { removal ->
        scope.launch {
            host.currentSnackbarData?.dismiss()
            var undone = false
            try {
                val result =
                    host.showFeedback(
                        message = if (removal.toTrash) trashMessage else message,
                        kind = FeedbackKind.Info,
                        actionLabel = undoLabel,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    undone = true
                    removal.undo()
                }
            } finally {
                if (!undone) removal.finalize()
            }
        }
    }
}

/**
 * Lets a screen end a photo strip's edit mode when the user taps anywhere outside the strip: the
 * strip registers itself while editing, and [photoEditHost] on the screen's root watches touches.
 */
class PhotoEditHost {
    private var dismiss: (() -> Unit)? = null
    private var bounds: () -> Rect? = { null }

    internal fun activate(stop: () -> Unit, stripBounds: () -> Rect?) {
        if (dismiss != null && dismiss !== stop) dismiss?.invoke()
        dismiss = stop
        bounds = stripBounds
    }

    internal fun deactivate(stop: () -> Unit) {
        if (dismiss === stop) dismiss = null
    }

    internal fun touchDown(positionInWindow: Offset) {
        val stop = dismiss ?: return
        val strip = bounds()
        if (strip == null || !strip.contains(positionInWindow)) stop()
    }
}

val LocalPhotoEditHost = compositionLocalOf<PhotoEditHost?> { null }

/** Wraps a screen: provides a [PhotoEditHost] and ends photo edit mode on outside touches. */
@Composable
fun PhotoEditHostProvider(content: @Composable (Modifier) -> Unit) {
    val host = remember { PhotoEditHost() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalPhotoEditHost provides host) {
        content(
            Modifier.onGloballyPositioned { origin = it.positionInWindow() }
                .pointerInput(host) {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        host.touchDown(down.position + origin)
                    }
                }
        )
    }
}

/** Saturated delete red, fixed in both themes; white on it is at least 4.5:1 (5.6 and 4.6). */
internal val DeleteRedLight = Color(0xFFC62828)
internal val DeleteRedDark = Color(0xFFD93A3A)

/** One thumbnail in an [EditorPhotoStrip]: a server URL or a local file path for [model]. */
data class EditorPhoto(
    val key: String,
    val model: Any?,
    val description: String,
    val onRemove: () -> Unit,
    /** Opens the photo editor; set only for photos already saved on the server. */
    val onEdit: (() -> Unit)? = null,
)

/**
 * Makes the first [count] photos of an [EditorPhotoStrip] reorderable (the ones already on the
 * server; photos still waiting to upload follow them and have no id to order yet). [onMove] moves
 * the photo at [from] to position [to]. The first photo is the cover.
 */
class PhotoReorder(
    val count: Int,
    /** False for groups without a cover (step photos): the hint then omits the cover sentence. */
    val firstIsCover: Boolean = true,
    val onMove: (from: Int, to: Int) -> Unit,
)

/**
 * A strip of photo thumbnails with an add tile. With [reorder], long-pressing a photo enters the
 * expanded reorder grid ([PhotoReorderGrid]); Move earlier / Move later / Move to first are also
 * offered to TalkBack as actions, and Alt+arrow keys move the focused photo. A photo tile shows no
 * star or drag handle, only the trash icon in edit mode; the first photo is the cover. Animation of
 * the moving thumbnails is skipped when animations are turned off.
 */
@Composable
fun EditorPhotoStrip(
    photos: List<EditorPhoto>,
    addLabel: String,
    removeLabel: String,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailSize: Int = 104,
    reorder: PhotoReorder? = null,
    takePhotoLabel: String? = null,
    onTakePhoto: (() -> Unit)? = null,
    editLabel: String? = null,
) {
    val listState = rememberLazyListState()
    val reduceMotion = animationsDisabled()
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spacingPx = with(LocalDensity.current) { 10.dp.toPx() }
    val currentReorder by rememberUpdatedState(reorder)
    // Edit mode (long-press any photo): every tile jiggles and shows its trash; selectedKey is the
    // photo that was pressed. interaction restarts the idle timer that ends the mode.
    var editing by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var stripBounds by remember { mutableStateOf<Rect?>(null) }
    val editHost = LocalPhotoEditHost.current
    // Reorder mode: long-press expands the strip into a larger grid until Done.
    val gridMode = editing && reorder != null && reorder.count > 1
    val stopEditing = {
        editing = false
        selectedKey = null
    }

    // The editor fills the strip after it first shows only the add tile; Lazy lists keep the item
    // they were anchored on (the add tile), so jump back to the first photo once photos arrive.
    val hasPhotos = photos.isNotEmpty()
    LaunchedEffect(hasPhotos) { if (hasPhotos) listState.scrollToItem(0) }

    // Edit mode ends on Back, a tap outside the strip (see [PhotoEditHost]), or after 5s idle.
    BackHandler(enabled = editing && !gridMode) { stopEditing() }
    LaunchedEffect(editing, interaction, gridMode) {
        if (editing && !gridMode) {
            delay(EDIT_IDLE_MILLIS)
            stopEditing()
        }
    }
    LaunchedEffect(photos.map { it.key }) {
        if (selectedKey !in photos.map { it.key }) selectedKey = null
        if (photos.isEmpty()) editing = false
    }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) interaction++ }
    DisposableEffect(editing, gridMode) {
        if (editing && !gridMode) editHost?.activate(stopEditing) { stripBounds }
        onDispose { editHost?.deactivate(stopEditing) }
    }

    if (gridMode && reorder != null) {
        PhotoReorderGrid(
            photos = photos,
            reorder = reorder,
            removeLabel = removeLabel,
            onDone = stopEditing,
            modifier = modifier,
        )
        return
    }
    LazyRow(
        state = listState,
        modifier =
            modifier.fillMaxWidth().onGloballyPositioned { stripBounds = it.boundsInWindow() },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(photos, key = { _, photo -> photo.key }) { index, photo ->
            val reorderable = reorder != null && index < reorder.count
            val selected = selectedKey == photo.key
            // Eased in and out so edit mode settles smoothly; the loop only runs while there is
            // something to show, and is read inside graphicsLayer so the strip does not recompose.
            val jiggleAmount =
                animateFloatAsState(
                    if (editing && !reduceMotion) 1f else 0f,
                    animationSpec = tween(180),
                    label = "jiggle-amount",
                )
            val period = 260 + (index * 23) % 40
            val jigglePhase: State<Float> =
                if (jiggleAmount.value > 0f) {
                    rememberInfiniteTransition(label = "photo-jiggle")
                        .animateFloat(
                            initialValue = -1f,
                            targetValue = 1f,
                            animationSpec =
                                infiniteRepeatable(
                                    tween(period, easing = LinearEasing),
                                    RepeatMode.Reverse,
                                    initialStartOffset = StartOffset((index * 61) % period),
                                ),
                            label = "jiggle",
                        )
                } else {
                    remember { mutableStateOf(0f) }
                }
            val earlierLabel = stringResource(R.string.editor_photo_move_earlier)
            val laterLabel = stringResource(R.string.editor_photo_move_later)
            val moveFirstLabel = stringResource(R.string.editor_photo_move_first)
            val canMoveFirst = reorderable && canMoveToFirst(index, reorder!!.count)
            val positionLabel =
                if (reorderable) {
                    stringResource(R.string.editor_photo_position, index + 1, reorder!!.count)
                } else null
            Box(
                Modifier.size(thumbnailSize.dp)
                    // Always present (a changing modifier chain would restart the gestures below);
                    // reduced motion just gets no placement animation.
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = if (reduceMotion) null else spring(),
                        fadeOutSpec = null,
                    )
                    // A tap in edit mode just keeps it alive; tapping outside the strip ends it.
                    .pointerInput(photo.key) { detectTapGestures(onTap = { interaction++ }) }
                    // Long-press: enter edit mode with this photo selected. With reordering the
                    // mode is the expanded grid (see [PhotoReorderGrid]); dragging happens there.
                    .pointerInput(photo.key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                editing = true
                                selectedKey = photo.key
                                interaction++
                            },
                            onDrag = { change, _ -> change.consume() },
                        )
                    }
                    .graphicsLayer {
                        val amount = jiggleAmount.value
                        rotationZ = jigglePhase.value * JIGGLE_DEGREES * amount
                        translationY = -abs(jigglePhase.value) * JIGGLE_BOB_DP.dp.toPx() * amount
                    }
                    .focusable()
                    .semantics(mergeDescendants = true) {
                        positionLabel?.let { stateDescription = it }
                        customActions =
                            listOfNotNull(
                                if (reorderable && index > 0) {
                                    CustomAccessibilityAction(earlierLabel) {
                                        currentReorder?.onMove?.invoke(index, index - 1)
                                        true
                                    }
                                } else null,
                                if (reorderable && index < (reorder?.count ?: 0) - 1) {
                                    CustomAccessibilityAction(laterLabel) {
                                        currentReorder?.onMove?.invoke(index, index + 1)
                                        true
                                    }
                                } else null,
                                if (canMoveFirst) {
                                    CustomAccessibilityAction(moveFirstLabel) {
                                        currentReorder?.onMove?.invoke(index, 0)
                                        true
                                    }
                                } else null,
                                CustomAccessibilityAction(removeLabel) {
                                    photo.onRemove()
                                    true
                                },
                            )
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.Delete || event.key == Key.Backspace) {
                            photo.onRemove()
                            return@onPreviewKeyEvent true
                        }
                        val move = currentReorder
                        if (move == null || !reorderable || !event.isAltPressed) {
                            return@onPreviewKeyEvent false
                        }
                        val step =
                            when (event.key) {
                                Key.DirectionLeft -> if (rtl) 1 else -1
                                Key.DirectionRight -> if (rtl) -1 else 1
                                else -> return@onPreviewKeyEvent false
                            }
                        val target = index + step
                        if (target in 0 until move.count) move.onMove(index, target)
                        true
                    }
            ) {
                AsyncImage(
                    model = photo.model,
                    contentDescription = photo.description.ifBlank { null },
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.size(thumbnailSize.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.shapes.medium,
                                    )
                                } else Modifier
                            ),
                )
                val onEdit = photo.onEdit
                if (onEdit != null && editLabel != null && !editing) {
                    // Crop and rotate: a small dark badge, bottom end, with a 44dp touch target.
                    IconButton(
                        onClick = {
                            interaction++
                            onEdit()
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp).size(44.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.CropRotate,
                                    contentDescription = editLabel,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                if (editing) {
                    // 48dp touch target, small visible red badge; only in edit mode.
                    IconButton(
                        onClick = {
                            interaction++
                            photo.onRemove()
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(1.dp).size(44.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color =
                                if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                                    DeleteRedDark
                                } else DeleteRedLight,
                            contentColor = Color.White,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    PhosphorIcons.Trash,
                                    contentDescription = removeLabel,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "add") {
            AddPhotoTile(Icons.Filled.AddAPhoto, addLabel, thumbnailSize, onAdd)
        }
        // Beside the gallery pick: only where a camera app can take the picture.
        if (onTakePhoto != null && takePhotoLabel != null) {
            item(key = "take") {
                AddPhotoTile(PhosphorIcons.Camera, takePhotoLabel, thumbnailSize, onTakePhoto)
            }
        }
    }
}

/** A dashed "add" tile at the end of an [EditorPhotoStrip] (gallery pick or camera). */
@Composable
private fun AddPhotoTile(icon: ImageVector, label: String, size: Int, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.size(size.dp)
                .stitchedBorder(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    inset = 3.dp,
                    cornerRadius = 19.dp,
                )
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private const val EDIT_IDLE_MILLIS = 5_000L
private const val JIGGLE_DEGREES = 1.8f
private const val JIGGLE_BOB_DP = 0.8f

/**
 * A text field that suggests common or existing values as you type (a combo box). Typing anything
 * else is fine - it is still free text, like on the website.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    labelIcon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered =
        remember(value, suggestions) {
            val query = value.trim()
            suggestions
                .distinct()
                .filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }
                .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
                .take(8)
        }
    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { FieldLabel(label, labelIcon) },
            singleLine = true,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            modifier =
                Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            filtered.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * The editor's Save button, pinned to the bottom and lifted above the keyboard, so saving never
 * needs a reach to the top corner or closing the keyboard first.
 */
@Composable
fun EditorSaveBar(
    isSaving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    /** An existing record with nothing pending: the button is disabled and says so. */
    allSaved: Boolean = false,
    /** The dirty-state line above the button ("Unsaved changes ..."); null shows none. */
    statusText: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (statusText != null) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Button(
                onClick = onSave,
                enabled = !isSaving && !allSaved,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (isSaving) {
                    YarnLoader(size = 26.dp, modifier = Modifier.padding(end = 8.dp))
                }
                Text(
                    stringResource(
                        if (allSaved) R.string.editor_all_saved else R.string.common_save
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** One server photo waiting to be removed on Save, as a row of [StagedRemovalList]. */
data class StagedPhoto(
    val key: String,
    val model: Any?,
    val label: String,
    val onUndo: () -> Unit,
)

/** The label of a staged photo: its description, else "Photo N" ([number] is its old place). */
@Composable
fun stagedPhotoLabel(description: String, number: Int): String = description.ifBlank {
    stringResource(R.string.editor_staged_photo_label, number)
}

/**
 * "Will be removed when you save": the server photos taken out of the form, each as a dimmed
 * thumbnail with a struck-through label and an Undo button, plus the trash-retention hint. Draws
 * nothing when [photos] is empty.
 */
@Composable
fun StagedRemovalList(photos: List<StagedPhoto>, modifier: Modifier = Modifier) {
    if (photos.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.editor_staged_title),
            style = MaterialTheme.typography.labelLarge,
        )
        photos.forEach { photo ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = photo.model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.45f,
                    modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                )
                Text(
                    photo.label,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = photo.onUndo) { Text(stringResource(R.string.common_undo)) }
            }
        }
        Text(
            stringResource(R.string.editor_staged_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Discard your changes?" - shown when leaving an editor with unsaved edits. */
@Composable
fun DiscardChangesDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = { Text(stringResource(R.string.editor_discard_text)) },
        confirmButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.editor_discard_keep))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    stringResource(R.string.editor_discard_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/** A thin outlined row used for attachments and linked yarns. */
@Composable
fun EditorListRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            leading()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = removeLabel)
            }
        }
    }
}

/**
 * The host of a valid web link for display ("garnstudio.com"), or null when [value] is not an
 * http(s) URL with a host. Surrounding whitespace is ignored.
 */
fun urlHostOrNull(value: String): String? {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    return uri.host?.removePrefix("www.")?.takeIf { it.contains('.') || it == "localhost" }
}

/**
 * A link field shared by the project and yarn editors: the URL wraps over up to four lines instead
 * of being cut off, breaks anywhere without hyphens, stays a single line of text (newlines are
 * dropped), and offers Open (valid links) and Clear (while focused) inside the field. The domain
 * shows below when the field is not being edited; text that is not a web link shows an error.
 */
@Composable
fun UrlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onFocusLost: ((String) -> Unit)? = null,
    labelIcon: ImageVector? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val host = urlHostOrNull(value)
    val invalid = value.isNotBlank() && host == null
    val openLabel = stringResource(R.string.editor_link_open)
    val clearLabel = stringResource(R.string.editor_link_clear)
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filterNot { char -> char == '\n' || char == '\r' }) },
        label = { FieldLabel(label, labelIcon) },
        placeholder = { Text("https://") },
        textStyle =
            MaterialTheme.typography.bodyMedium.copy(
                lineBreak = LineBreak.Simple,
                hyphens = Hyphens.None,
            ),
        singleLine = false,
        minLines = 1,
        maxLines = 4,
        isError = invalid,
        supportingText =
            when {
                invalid -> {
                    { Text(stringResource(R.string.editor_link_invalid)) }
                }
                host != null && !focused -> {
                    { Text(host) }
                }
                else -> null
            },
        trailingIcon =
            if (host != null || (focused && value.isNotEmpty())) {
                {
                    Row {
                        if (host != null) {
                            IconButton(
                                onClick = {
                                    runCatching { uriHandler.openUri(value.trim()) }
                                }
                            ) {
                                Icon(PhosphorIcons.ArrowSquareOut, contentDescription = openLabel)
                            }
                        }
                        if (focused && value.isNotEmpty()) {
                            IconButton(onClick = { onValueChange("") }) {
                                Icon(PhosphorIcons.X, contentDescription = clearLabel)
                            }
                        }
                    }
                }
            } else null,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier =
            modifier.onFocusChanged {
                if (focused && !it.isFocused) onFocusLost?.invoke(value)
                focused = it.isFocused
            },
    )
}

/**
 * A field label with a small decorative icon before the text, for an `OutlinedTextField` label slot
 * (the web editors do the same with `LabelIcon`). The icon takes the label colour, has no content
 * description, and the label text, so the field's accessible name, is unchanged.
 */
@Composable
fun FieldLabel(text: String, icon: ImageVector?) {
    if (icon == null) {
        Text(text)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}
