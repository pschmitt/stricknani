package blue.anika.wolle.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import blue.anika.wolle.R
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.isActive
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
class PhotoRemoval(val undo: () -> Unit, val finalize: () -> Unit = {})

/**
 * Returns a function that shows the "Photo removed" snackbar with Undo for a [PhotoRemoval] on
 * [host]. A newer removal replaces the snackbar of an older one, which then becomes final.
 */
@Composable
fun rememberPhotoRemovalAnnouncer(host: SnackbarHostState): (PhotoRemoval) -> Unit {
    val scope = rememberCoroutineScope()
    val message = stringResource(R.string.editor_photo_removed)
    val undoLabel = stringResource(R.string.common_undo)
    return { removal ->
        scope.launch {
            host.currentSnackbarData?.dismiss()
            var undone = false
            try {
                val result =
                    host.showFeedback(
                        message = message,
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

/** Saturated delete red, fixed in both themes; white on it is at least 4.5:1 (5.6 and 4.6). */
private val DeleteRedLight = Color(0xFFC62828)
private val DeleteRedDark = Color(0xFFD93A3A)

/** One thumbnail in an [EditorPhotoStrip]: a server URL or a local file path for [model]. */
data class EditorPhoto(
    val key: String,
    val model: Any?,
    val description: String,
    val onRemove: () -> Unit,
)

/**
 * Makes the first [count] photos of an [EditorPhotoStrip] reorderable (the ones already on the
 * server; photos still waiting to upload follow them and have no id to order yet). [onMove] moves
 * the photo at [from] to position [to]. The first photo is the cover.
 */
class PhotoReorder(val count: Int, val onMove: (from: Int, to: Int) -> Unit)

/**
 * A strip of photo thumbnails with an add tile. With [reorder], each server photo gets a drag
 * handle: long-press a thumbnail and drag it sideways, or tap the handle for Move earlier / Move
 * later (also offered to TalkBack as actions, and as Alt+arrow keys). The first photo wears a cover
 * tag. Animation of the moving thumbnails is skipped when animations are turned off.
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
) {
    val listState = rememberLazyListState()
    val reduceMotion = animationsDisabled()
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spacingPx = with(LocalDensity.current) { 10.dp.toPx() }
    val currentReorder by rememberUpdatedState(reorder)
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var startOffset by remember { mutableIntStateOf(0) }

    // The editor fills the strip after it first shows only the add tile; Lazy lists keep the item
    // they were anchored on (the add tile), so jump back to the first photo once photos arrive.
    val hasPhotos = photos.isNotEmpty()
    LaunchedEffect(hasPhotos) { if (hasPhotos) listState.scrollToItem(0) }

    // Leave the selected state when the photo goes away, the strip scrolls or Back is pressed.
    BackHandler(enabled = selectedKey != null) { selectedKey = null }
    LaunchedEffect(photos.map { it.key }) {
        if (selectedKey !in photos.map { it.key }) selectedKey = null
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && draggedKey == null) selectedKey = null
    }

    // While a thumbnail is held: keep the strip scrolling near its edges and swap places whenever
    // the held thumbnail's centre crosses another reorderable one.
    LaunchedEffect(draggedKey) {
        val key = draggedKey ?: return@LaunchedEffect
        // After a swap the layout still shows the old order for a frame; wait until the held
        // thumbnail reports its new index so the same swap is not undone straight away.
        var awaitingIndex: Int? = null
        var settleFrames = 0
        while (isActive) {
            withFrameNanos {}
            val layout = listState.layoutInfo
            val held = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
            awaitingIndex?.let { expected ->
                if (held.index != expected && ++settleFrames < MAX_SETTLE_FRAMES) continue
                awaitingIndex = null
            }
            val left = startOffset + dragDelta
            val edge = held.size * 0.35f
            when {
                left < layout.viewportStartOffset + edge -> listState.scrollBy(-EDGE_SCROLL_STEP)
                left + held.size > layout.viewportEndOffset - edge ->
                    listState.scrollBy(EDGE_SCROLL_STEP)
            }
            val stride = held.size + spacingPx
            val count = currentReorder?.count ?: 0
            // Offset from where the held photo's own slot currently is (the list may re-anchor its
            // scroll to follow the moved photo, so absolute positions are not stable).
            val slot =
                (held.index + Math.round((left - held.offset) / stride)).coerceIn(
                    0,
                    (count - 1).coerceAtLeast(0),
                )
            if (held.index < count && slot != held.index) {
                // A moved item makes a lazy list re-anchor its scroll on it; keep the strip where
                // it
                // was so the other photos do not appear to jump under the finger.
                val anchorIndex = listState.firstVisibleItemIndex
                val anchorOffset = listState.firstVisibleItemScrollOffset
                currentReorder?.onMove?.invoke(held.index, slot)
                withFrameNanos {}
                listState.scrollToItem(anchorIndex, anchorOffset)
                awaitingIndex = slot
                settleFrames = 0
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(photos, key = { _, photo -> photo.key }) { index, photo ->
            val reorderable = reorder != null && index < reorder.count
            val held = draggedKey == photo.key
            val selected = selectedKey == photo.key
            val lift by
                animateFloatAsState(
                    if (held) 1.06f else 1f,
                    animationSpec = if (reduceMotion) snap() else spring(),
                    label = "photo-lift",
                )
            val wiggleState: State<Float> =
                if (selected && !held && !reduceMotion) {
                    rememberInfiniteTransition(label = "photo-wiggle")
                        .animateFloat(
                            initialValue = -1.5f,
                            targetValue = 1.5f,
                            animationSpec = infiniteRepeatable(tween(110), RepeatMode.Reverse),
                            label = "wiggle",
                        )
                } else {
                    remember { mutableStateOf(0f) }
                }
            val wiggle = wiggleState.value
            val earlierLabel = stringResource(R.string.editor_photo_move_earlier)
            val laterLabel = stringResource(R.string.editor_photo_move_later)
            val positionLabel =
                if (reorderable) {
                    stringResource(R.string.editor_photo_position, index + 1, reorder!!.count)
                } else null
            Box(
                Modifier.size(thumbnailSize.dp)
                    // Always present (a changing modifier chain would restart the gestures below);
                    // the held photo and reduced motion just get no placement animation.
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = if (held || reduceMotion) null else spring(),
                        fadeOutSpec = null,
                    )
                    .zIndex(if (held) 1f else 0f)
                    // Tap another photo (or scroll, or Back) to leave the selected state.
                    .pointerInput(photo.key) { detectTapGestures(onTap = { selectedKey = null }) }
                    // Long-press: select the photo (delete shows up); keep dragging to reorder.
                    .pointerInput(photo.key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val info =
                                    listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                        it.key == photo.key
                                    }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedKey = photo.key
                                if (info != null && reorderable) {
                                    startOffset = info.offset
                                    dragDelta = 0f
                                    draggedKey = photo.key
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (draggedKey == photo.key) {
                                    dragDelta += if (rtl) -amount.x else amount.x
                                }
                            },
                            onDragEnd = {
                                // Pressed without moving: stay selected. A real drag just reorders.
                                if (abs(dragDelta) > DRAG_SLOP_PX) selectedKey = null
                                draggedKey = null
                            },
                            onDragCancel = { draggedKey = null },
                        )
                    }
                    // Outside the layer below, so the pointer coordinates do not move with the
                    // lift.
                    .graphicsLayer {
                        rotationZ = wiggle
                        if (held) {
                            val info =
                                listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    it.key == photo.key
                                }
                            val shift = dragDelta + startOffset - (info?.offset ?: startOffset)
                            translationX = shift
                            scaleX = lift
                            scaleY = lift
                            shadowElevation = 12.dp.toPx()
                            shape = RoundedCornerShape(16.dp)
                        }
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
                if (reorder != null && index == 0) {
                    CoverTag(Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
                if (selected) {
                    // 48dp touch target, small visible red badge; only after a long-press.
                    IconButton(
                        onClick = {
                            selectedKey = null
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
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.size(thumbnailSize.dp)
                        .stitchedBorder(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            inset = 3.dp,
                            cornerRadius = 19.dp,
                        )
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button, onClick = onAdd),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(8.dp),
                ) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null)
                    Text(
                        addLabel,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private const val EDGE_SCROLL_STEP = 14f
private const val DRAG_SLOP_PX = 8f
private const val MAX_SETTLE_FRAMES = 6

/** The "Cover" tag on the first photo: a yarn-ball chip in the theme's primary container. */
@Composable
private fun CoverTag(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Icon(PhosphorIcons.Yarn, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(
                stringResource(R.string.editor_photo_cover),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

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
            label = { Text(label) },
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
fun EditorSaveBar(isSaving: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Button(
                onClick = onSave,
                enabled = !isSaving,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (isSaving) {
                    YarnLoader(size = 26.dp, modifier = Modifier.padding(end = 8.dp))
                }
                Text(
                    stringResource(R.string.common_save),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
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
