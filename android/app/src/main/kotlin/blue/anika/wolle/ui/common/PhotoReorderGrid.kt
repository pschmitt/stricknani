package blue.anika.wolle.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.canMoveToFirst
import coil3.compose.AsyncImage
import kotlin.math.ceil
import kotlin.math.floor

/** The smallest a thumbnail gets in reorder mode; phones always show at least three columns. */
private const val GRID_MIN_TILE_DP = 110
private const val GRID_MIN_COLUMNS = 3
private const val GRID_GAP_DP = 10

/** Web parity (`edgeScrollDelta` in `media-reorder.ts`): the edge zone and the top speed. */
internal const val EDGE_SCROLL_ZONE_DP = 72f
internal const val EDGE_SCROLL_MAX_SPEED_DP = 18f

/** One 60 Hz frame; the speed is per such frame and scaled by the real frame time. */
private const val EDGE_SCROLL_FRAME_MILLIS = 1000f / 60f

/**
 * Scroll step for one frame while a lifted photo is held at [pointerY], inside the visible span
 * [top]..[bottom] of the scroll container: zero in the middle, negative (scroll up) within [zone]
 * of the top and positive (scroll down) within [zone] of the bottom, growing linearly with the
 * depth into the zone up to [maxSpeed]. The same curve as the web's `edgeScrollDelta`.
 */
internal fun edgeScrollDelta(
    pointerY: Float,
    top: Float,
    bottom: Float,
    zone: Float,
    maxSpeed: Float,
): Float {
    if (zone <= 0f) return 0f
    if (pointerY < top + zone) {
        val depth = ((top + zone - pointerY) / zone).coerceAtMost(1f)
        return -ceil(depth * maxSpeed)
    }
    if (pointerY > bottom - zone) {
        val depth = ((pointerY - (bottom - zone)) / zone).coerceAtMost(1f)
        return ceil(depth * maxSpeed)
    }
    return 0f
}

/** How many columns fit [widthDp]: at least three, more on wider screens. */
internal fun photoGridColumns(widthDp: Float): Int =
    ((widthDp + GRID_GAP_DP) / (GRID_MIN_TILE_DP + GRID_GAP_DP))
        .toInt()
        .coerceAtLeast(GRID_MIN_COLUMNS)

/**
 * The slot a thumbnail dragged to ([centerX], [centerY]) belongs in, in a grid whose cells are
 * [stride] apart: row by row, left to right, never past the last of the [reorderableCount]
 * reorderable photos.
 */
internal fun photoGridSlot(
    centerX: Float,
    centerY: Float,
    stride: Float,
    columns: Int,
    rows: Int,
    reorderableCount: Int,
): Int {
    val column = floor(centerX / stride).toInt().coerceIn(0, columns - 1)
    val row = floor(centerY / stride).toInt().coerceIn(0, (rows - 1).coerceAtLeast(0))
    return (row * columns + column).coerceIn(0, (reorderableCount - 1).coerceAtLeast(0))
}

/**
 * The expanded layout of [EditorPhotoStrip]'s reorder mode: every photo as a large thumbnail in a
 * multi-row grid. Long-press a thumbnail and drag it to any place (no handle, no cover star: the
 * first photo is the cover); Move earlier / Move later / Move to first are also offered as
 * accessibility actions, Alt+arrow keys move the focused photo. Only the trash icon is on a tile.
 * [onDone] leaves the mode. Placement animation is skipped when animations are off.
 */
@Composable
internal fun PhotoReorderGrid(
    photos: List<EditorPhoto>,
    reorder: PhotoReorder,
    removeLabel: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = animationsDisabled()
    val haptics = LocalHapticFeedback.current
    val currentPhotos by rememberUpdatedState(photos)
    val currentReorder by rememberUpdatedState(reorder)
    var heldKey by remember { mutableStateOf<String?>(null) }
    var startPos by remember { mutableStateOf(Offset.Zero) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    // Where inside the tile the finger grabbed it, to know the finger's place while scrolling.
    var grab by remember { mutableStateOf(Offset.Zero) }
    val gridCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val scrollHost = LocalPhotoEditHost.current
    BackHandler(onBack = onDone)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (reorder.firstIsCover) {
                        R.string.editor_photo_reorder_hint
                    } else R.string.editor_photo_reorder_hint_plain
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) { Text(stringResource(R.string.common_done)) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = photoGridColumns(maxWidth.value)
            val gap = GRID_GAP_DP.dp
            val tile = (maxWidth - gap * (columns - 1)) / columns
            val density = LocalDensity.current
            val tilePx = with(density) { tile.toPx() }
            val stride = tilePx + with(density) { gap.toPx() }
            val rows = ceil(photos.size / columns.toFloat()).toInt().coerceAtLeast(1)
            fun slotOffset(index: Int) =
                Offset((index % columns) * stride, (index / columns) * stride)

            fun moveHeldTo(centerX: Float, centerY: Float) {
                val key = heldKey ?: return
                val from = currentPhotos.indexOfFirst { it.key == key }
                if (from < 0 || from >= currentReorder.count) return
                val to =
                    photoGridSlot(centerX, centerY, stride, columns, rows, currentReorder.count)
                if (to != from) {
                    currentReorder.onMove(from, to)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }

            fun startDrag(key: String, grabOffset: Offset) {
                val index = currentPhotos.indexOfFirst { it.key == key }
                if (index < 0 || index >= currentReorder.count) return
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                heldKey = key
                grab = grabOffset
                startPos = slotOffset(index)
                delta = Offset.Zero
            }

            fun drag(amount: Offset) {
                delta += amount
                val center = startPos + delta + Offset(tilePx / 2, tilePx / 2)
                moveHeldTo(center.x, center.y)
            }

            // While a photo is held near the top or bottom edge of the editor's visible area,
            // scroll
            // it, and keep the photo under the finger: the grid moves by what was scrolled, so the
            // held photo's place in the grid moves by the same, and the slot is looked up again.
            val zonePx = with(density) { EDGE_SCROLL_ZONE_DP.dp.toPx() }
            val maxSpeedPx = with(density) { EDGE_SCROLL_MAX_SPEED_DP.dp.toPx() }
            LaunchedEffect(heldKey, stride, columns, rows) {
                if (heldKey == null) return@LaunchedEffect
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val scale =
                        ((now - last) / 1_000_000f / EDGE_SCROLL_FRAME_MILLIS).coerceIn(0f, 3f)
                    last = now
                    val scroller = scrollHost?.scroller ?: continue
                    val viewport = scroller.viewport() ?: continue
                    val origin =
                        gridCoords[0]?.takeIf { it.isAttached }?.positionInWindow() ?: continue
                    val pointerY = origin.y + startPos.y + grab.y + delta.y
                    val step =
                        edgeScrollDelta(pointerY, viewport.top, viewport.bottom, zonePx, maxSpeedPx)
                    if (step == 0f) continue
                    val consumed = scroller.scrollBy(step * scale)
                    if (consumed != 0f) {
                        delta += Offset(0f, consumed)
                        val center = startPos + delta + Offset(tilePx / 2, tilePx / 2)
                        moveHeldTo(center.x, center.y)
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth()
                    .height(tile * rows + gap * (rows - 1))
                    .onGloballyPositioned { gridCoords[0] = it }
            ) {
                photos.forEachIndexed { index, photo ->
                    key(photo.key) {
                        val reorderable = index < reorder.count
                        val held = heldKey == photo.key
                        val slot = slotOffset(index)
                        val animated by
                            animateIntOffsetAsState(
                                IntOffset(slot.x.toInt(), slot.y.toInt()),
                                animationSpec = if (reduceMotion || held) snap() else spring(),
                                label = "photo-slot",
                            )
                        val earlierLabel = stringResource(R.string.editor_photo_move_earlier)
                        val laterLabel = stringResource(R.string.editor_photo_move_later)
                        val moveFirstLabel = stringResource(R.string.editor_photo_move_first)
                        val positionLabel =
                            if (reorderable) {
                                stringResource(
                                    R.string.editor_photo_position,
                                    index + 1,
                                    reorder.count,
                                )
                            } else null
                        Box(
                            Modifier.size(tile)
                                .offset {
                                    if (held) {
                                        IntOffset(
                                            (startPos.x + delta.x).toInt(),
                                            (startPos.y + delta.y).toInt(),
                                        )
                                    } else animated
                                }
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer {
                                    if (held) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                        shadowElevation = 12.dp.toPx()
                                        shape = RoundedCornerShape(16.dp)
                                    }
                                }
                                .then(
                                    if (reorderable) {
                                        Modifier.pointerInput(photo.key) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    startDrag(photo.key, offset)
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    drag(amount)
                                                },
                                                onDragEnd = { heldKey = null },
                                                onDragCancel = { heldKey = null },
                                            )
                                        }
                                    } else Modifier
                                )
                                .focusable()
                                .semantics(mergeDescendants = true) {
                                    positionLabel?.let { stateDescription = it }
                                    customActions =
                                        listOfNotNull(
                                            if (reorderable && index > 0) {
                                                CustomAccessibilityAction(earlierLabel) {
                                                    currentReorder.onMove(index, index - 1)
                                                    true
                                                }
                                            } else null,
                                            if (reorderable && index < reorder.count - 1) {
                                                CustomAccessibilityAction(laterLabel) {
                                                    currentReorder.onMove(index, index + 1)
                                                    true
                                                }
                                            } else null,
                                            if (
                                                reorderable && canMoveToFirst(index, reorder.count)
                                            ) {
                                                CustomAccessibilityAction(moveFirstLabel) {
                                                    currentReorder.onMove(index, 0)
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
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    if (event.key == Key.Delete || event.key == Key.Backspace) {
                                        photo.onRemove()
                                        return@onPreviewKeyEvent true
                                    }
                                    if (!reorderable || !event.isAltPressed) {
                                        return@onPreviewKeyEvent false
                                    }
                                    val step =
                                        when (event.key) {
                                            Key.DirectionLeft -> -1
                                            Key.DirectionRight -> 1
                                            Key.DirectionUp -> -columns
                                            Key.DirectionDown -> columns
                                            else -> return@onPreviewKeyEvent false
                                        }
                                    val target = index + step
                                    if (target in 0 until reorder.count) {
                                        currentReorder.onMove(index, target)
                                    }
                                    true
                                }
                        ) {
                            AsyncImage(
                                model = photo.model,
                                contentDescription = photo.description.ifBlank { null },
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier.size(tile)
                                        .clip(MaterialTheme.shapes.medium)
                                        .then(
                                            if (held) {
                                                Modifier.border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.shapes.medium,
                                                )
                                            } else Modifier
                                        ),
                            )
                            GridBadgeButton(
                                onClick = photo.onRemove,
                                modifier = Modifier.align(Alignment.TopEnd),
                                description = removeLabel,
                                destructive = true,
                            ) {
                                Icon(PhosphorIcons.Trash, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridBadgeButton(
    onClick: () -> Unit,
    modifier: Modifier,
    description: String,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.padding(1.dp).size(44.dp)) {
        Surface(
            shape = CircleShape,
            color =
                when {
                    !destructive -> Color.Black.copy(alpha = 0.6f)
                    MaterialTheme.colorScheme.surface.luminance() < 0.5f -> DeleteRedDark
                    else -> DeleteRedLight
                },
            contentColor = Color.White,
            modifier = Modifier.size(30.dp).semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}
