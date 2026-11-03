package blue.anika.wolle.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.theme.StickyPaperDark
import blue.anika.wolle.ui.theme.StickyPaperFoldDark
import blue.anika.wolle.ui.theme.StickyPaperFoldLight
import blue.anika.wolle.ui.theme.StickyPaperInkDark
import blue.anika.wolle.ui.theme.StickyPaperInkLight
import blue.anika.wolle.ui.theme.StickyPaperLight
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FAB_TILT = NotesFabMath.REST_TILT
private const val LIFT_SCALE = 0.12f
private const val LIFT_PIN_TILT = -6f
private const val CARD_TILT = -1.5f
private const val FAB_DP = 56f
private const val FAB_BOTTOM_DP = 16f
private const val WIDE_CARD_DP = 360f
private const val WIDE_BREAKPOINT_DP = 600f
private const val FAB_FOLD_DP = 12f
private const val CARD_FOLD_DP = 22.4f
private const val FAB_PIN_DP = 13.6f
private const val CARD_PIN_DP = 21.6f
private const val SCRIM_ALPHA = 0.28f
private val TextEasing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

/** [point] of [this] node in the coordinates of [root]. */
private fun LayoutCoordinates.positionInRootOf(root: LayoutCoordinates, point: Offset) =
    root.localPositionOf(this, point)

/** Phones centre the open card; wide screens anchor it to the side the note sits on. */
private fun cardCenterX(
    wide: Boolean,
    side: NotesFabSide,
    maxW: Float,
    inset: Float,
    cardW: Float,
) =
    when {
        !wide -> maxW / 2f
        side == NotesFabSide.Right -> maxW - inset - cardW / 2f
        else -> inset + cardW / 2f
    }

/** One small wobble of the pin, mirrored on the left. */
private fun swayKeyframes(side: NotesFabSide, fromRest: Boolean) =
    keyframes<Float> {
        durationMillis = 600
        val k = side.sign
        if (fromRest) FAB_TILT * k at 0
        9f * k at 108
        1f * k at 228
        6f * k at 336
        2.8f * k at 444
        4.5f * k at 528
        FAB_TILT * k at 600
    }

/**
 * The notes of a project or yarn as a pinned mini sticky note stuck to the right edge, which
 * unfolds into the full note: one progress value drives the paper's size, tilt, folded corner and
 * pin together, so closing is the exact reverse and a tap mid-way retraces smoothly. Everything
 * pivots at the pin (top centre); only the paper tilts, never the text. With animations off it is a
 * plain crossfade. Place it over the detail screen's content.
 */
@Composable
internal fun NotesDisclosure(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val paper = if (isLight) StickyPaperLight else StickyPaperDark
    val ink = if (isLight) StickyPaperInkLight else StickyPaperInkDark
    val foldColor = if (isLight) StickyPaperFoldLight else StickyPaperFoldDark
    val tape = MaterialTheme.colorScheme.primary
    val reduceMotion = animationsDisabled()

    var open by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open, reduceMotion) {
        if (reduceMotion) progress.animateTo(if (open) 1f else 0f, tween(160))
        else progress.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 400f))
    }
    val textIn = remember { Animatable(0f) }
    LaunchedEffect(open, reduceMotion) {
        when {
            reduceMotion -> textIn.snapTo(if (open) 1f else 0f)
            open -> {
                delay(120)
                textIn.animateTo(1f, tween(200, easing = TextEasing))
            }
            else -> textIn.animateTo(0f, tween(100))
        }
    }

    val fabViewModel: NotesFabViewModel = hiltViewModel()
    val storedPlacement by fabViewModel.placement.collectAsStateWithLifecycle()
    var placement by remember { mutableStateOf(NotesFabPlacement.Default) }
    var placed by remember { mutableStateOf(false) }
    val fabPos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val lift = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragTilt by remember { mutableFloatStateOf(FAB_TILT) }
    var dragVx by remember { mutableFloatStateOf(0f) }
    var lastDragMs by remember { mutableLongStateOf(0L) }
    val side = placement.side

    // The resting tilt (mirrored on the left) plus the sway of the pin settling.
    val sway = remember { Animatable(FAB_TILT) }
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress, open, reduceMotion, side) {
        if (listState.isScrollInProgress) {
            wasScrolling = true
        } else if (wasScrolling && !open && !reduceMotion) {
            wasScrolling = false
            delay(180)
            sway.animateTo(side.restTilt, swayKeyframes(side, fromRest = true))
        }
    }

    // Tilts with the horizontal speed while carried: the target follows the smoothed velocity,
    // which relaxes when the finger rests.
    LaunchedEffect(dragging, reduceMotion) {
        if (!dragging || reduceMotion) return@LaunchedEffect
        var prev = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - prev) / 1_000_000_000f
            prev = now
            if (now / 1_000_000L - lastDragMs > 60L) dragVx *= 0.8f
            val target = NotesFabMath.dragTilt(dragVx, side)
            dragTilt += (target - dragTilt) * (1f - exp(-dt * 18f))
        }
    }

    LaunchedEffect(storedPlacement) {
        val stored = storedPlacement
        if (stored != null && !placed) placement = stored
    }

    BackHandler(enabled = open) { open = false }

    val fabFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        delay(60)
        if (open) runCatching { closeFocus.requestFocus() }
        else if (wasOpen) runCatching { fabFocus.requestFocus() }
        wasOpen = open
    }

    val notesLabel = stringResource(R.string.project_detail_notes_title)
    val closeLabel = stringResource(R.string.notes_close)
    val expandedState = stringResource(R.string.notes_state_expanded)
    val collapsedState = stringResource(R.string.notes_state_collapsed)
    val hintText = stringResource(R.string.notes_fab_hint)
    val moveUpLabel = stringResource(R.string.notes_fab_move_up)
    val moveDownLabel = stringResource(R.string.notes_fab_move_down)
    val moveSideLabel = stringResource(R.string.notes_fab_move_side)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { rootCoords = it }) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val wide = maxWidth >= WIDE_BREAKPOINT_DP.dp
        val cardW = with(density) { if (wide) WIDE_CARD_DP.dp.toPx() else maxW * 0.92f }
        val fab = with(density) { FAB_DP.dp.toPx() }
        val endInset = with(density) { NotesFabMath.insetDp(maxWidth.value).dp.toPx() }
        val bottom = with(density) { FAB_BOTTOM_DP.dp.toPx() }
        val bandTop = NotesFabMath.bandTop(with(density) { NotesFabMath.EDGE_MARGIN_DP.dp.toPx() })
        val bandBottom = NotesFabMath.bandBottom(maxH, fab, bottom)

        fun restOffset(p: NotesFabPlacement) =
            Offset(
                NotesFabMath.restX(p.side, maxW, fab, endInset),
                NotesFabMath.yFromFraction(p.yFraction, bandTop, bandBottom),
            )

        // Re-clamps whenever the size or the stored value changes (rotation, split screen).
        LaunchedEffect(storedPlacement != null, maxW, maxH) {
            if (storedPlacement == null) return@LaunchedEffect
            fabPos.snapTo(restOffset(placement))
            if (!placed) sway.snapTo(placement.side.restTilt)
            placed = true
        }

        /** Animates to [target], saves it and plays the settle: spring, wobble, tick. */
        fun settle(target: NotesFabPlacement, fromDrag: Boolean) {
            val changed = target != placement
            placement = target
            fabViewModel.save(target)
            if (fromDrag || changed)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val to = restOffset(target)
            scope.launch {
                if (fromDrag) {
                    fabPos.snapTo(dragOffset)
                    sway.snapTo(if (reduceMotion) target.side.restTilt else dragTilt)
                    dragging = false
                }
                launch {
                    if (reduceMotion) fabPos.animateTo(to, tween(120))
                    else fabPos.animateTo(to, spring(dampingRatio = 0.7f, stiffness = 400f))
                }
                launch {
                    if (reduceMotion) lift.snapTo(0f)
                    else lift.animateTo(0f, tween(160, easing = LinearOutSlowInEasing))
                }
                launch {
                    if (reduceMotion) sway.snapTo(target.side.restTilt)
                    else sway.animateTo(target.side.restTilt, swayKeyframes(target.side, false))
                }
            }
        }

        val onPickUp by rememberUpdatedState {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            dragOffset = fabPos.value
            dragTilt = sway.value
            dragVx = 0f
            dragging = true
            scope.launch {
                fabPos.stop()
                sway.stop()
                if (reduceMotion) lift.snapTo(1f)
                else lift.animateTo(1f, tween(160, easing = LinearOutSlowInEasing))
            }
        }
        val onDragTo by rememberUpdatedState { topLeft: Offset, vxDpPerSecond: Float, nowMs: Long ->
            dragOffset =
                Offset(topLeft.x.coerceIn(0f, maxW - fab), topLeft.y.coerceIn(0f, maxH - fab))
            dragVx = dragVx + (vxDpPerSecond - dragVx) * 0.35f
            lastDragMs = nowMs
        }
        val onDrop by rememberUpdatedState {
            settle(
                NotesFabMath.drop(dragOffset.x, dragOffset.y, maxW, fab, bandTop, bandBottom),
                fromDrag = true,
            )
        }
        val currentPos = if (dragging) dragOffset else fabPos.value
        var contentH by remember { mutableFloatStateOf(0f) }
        val cardH = maxOf(contentH, fab)
        val p = progress.value

        if (open || p > 0.001f) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        if (wide) Color.Transparent
                        else Color.Black.copy(alpha = SCRIM_ALPHA * p.coerceIn(0f, 1f))
                    )
                    .clickable(
                        enabled = open,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        open = false
                    }
            )
        }

        /** One paper at geometry [g] (0 = the little note, 1 = the card), drawn at [alpha]. */
        @Composable
        fun Paper(g: Float, alpha: Float, showText: Float) {
            val sw = lerp(fab, cardW, g)
            val sh = lerp(fab, cardH, g)
            val fabCx = currentPos.x + fab / 2f
            val cardCx = cardCenterX(wide, side, maxW, endInset, cardW)
            val cx = lerp(fabCx, cardCx, g)
            val top = lerp(currentPos.y, maxH - bottom - cardH, g)
            val foldPx = with(density) { lerp(FAB_FOLD_DP, CARD_FOLD_DP, g).dp.toPx() }
            val radius = with(density) { lerp(8f, 14f, g).dp.toPx() }
            val shape = FoldedCornerShape(foldPx, radius)
            val fabTilt = if (dragging) dragTilt else sway.value
            val tilt = lerp(fabTilt, CARD_TILT, g)
            val lifted = lift.value * (1f - g)
            val scale = 1f + LIFT_SCALE * lifted
            val pinR = with(density) { lerp(FAB_PIN_DP, CARD_PIN_DP, g).dp.toPx() } / 2f
            val pinCy = top + with(density) { lerp(6f, 1.5f, g).dp.toPx() }
            val glyphAlpha = 1f - min(1f, g / 0.35f)

            // Lifted: a deeper shadow, offset down, from its own layer so it is not clipped.
            if (lifted > 0.001f) {
                Box(
                    Modifier.offset { IntOffset((cx - sw / 2f).roundToInt(), top.roundToInt()) }
                        .size(with(density) { sw.toDp() }, with(density) { sh.toDp() })
                        .graphicsLayer {
                            rotationZ = tilt
                            scaleX = scale
                            scaleY = scale
                            translationY = 4.dp.toPx() * lifted
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            shadowElevation = 10.dp.toPx() * lifted
                            this.shape = shape
                            clip = false
                            this.alpha = alpha
                        }
                )
            }

            // The paper: it alone tilts, around the pin.
            Box(
                Modifier.offset { IntOffset((cx - sw / 2f).roundToInt(), top.roundToInt()) }
                    .size(with(density) { sw.toDp() }, with(density) { sh.toDp() })
                    .graphicsLayer {
                        rotationZ = tilt
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        this.alpha = alpha
                        shadowElevation = 6.dp.toPx()
                        this.shape = shape
                        clip = true
                    }
                    .background(paper, shape)
                    .drawWithContent {
                        drawContent()
                        val flap =
                            Path().apply {
                                moveTo(size.width - foldPx, size.height)
                                lineTo(size.width, size.height - foldPx)
                                lineTo(size.width - foldPx, size.height - foldPx)
                                close()
                            }
                        drawPath(flap, foldColor)
                    }
            ) {
                if (glyphAlpha > 0f) {
                    Icon(
                        PhosphorIcons.ListNumbers,
                        contentDescription = null,
                        tint = ink.copy(alpha = glyphAlpha * 0.8f),
                        modifier = Modifier.align(Alignment.Center).size(22.dp),
                    )
                }
            }

            // The text, never rotated or scaled: clipped to the growing paper, faded in late.
            if (showText > 0f) {
                Box(
                    Modifier.offset { IntOffset((cx - sw / 2f).roundToInt(), top.roundToInt()) }
                        .size(with(density) { sw.toDp() }, with(density) { sh.toDp() })
                        .clipToBounds(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        Modifier.requiredWidth(with(density) { cardW.toDp() })
                            .requiredHeightIn(max = with(density) { (maxH * 0.7f).toDp() })
                            .onSizeChanged { contentH = it.height.toFloat() }
                            .graphicsLayer {
                                this.alpha = showText
                                translationY = (1f - showText) * 10.dp.toPx()
                            }
                            .verticalScroll(rememberScrollState())
                            .padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 22.dp)
                    ) {
                        Text(
                            notesLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = ink,
                        )
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.material3.LocalContentColor provides ink
                        ) {
                            content()
                        }
                    }
                }
            }

            // The pin stays anchored at the top centre and does not tilt.
            Box(
                Modifier.offset {
                        IntOffset((cx - pinR).roundToInt(), (pinCy - pinR).roundToInt())
                    }
                    .size(with(density) { (pinR * 2).toDp() })
                    .graphicsLayer {
                        this.alpha = alpha
                        rotationZ = if (reduceMotion) 0f else LIFT_PIN_TILT * lifted
                    }
                    .drawBehind {
                        drawPin(tape, Offset(size.width / 2f, size.width / 2f), size.width / 2f)
                    }
            )
        }

        if (reduceMotion) {
            if (p < 1f) Paper(0f, 1f - p, 0f)
            if (p > 0f) Paper(1f, p, p)
        } else {
            Paper(p, 1f, textIn.value)
        }

        // Touch targets. Collapsed: the little note opens the notes. Open: the pin and the cross
        // close them (and the scrim, Back).
        val g = if (reduceMotion) (if (open) 1f else 0f) else p
        val cardCx = cardCenterX(wide, side, maxW, endInset, cardW)
        val cx = lerp(currentPos.x + fab / 2f, cardCx, g)
        val top = lerp(currentPos.y, maxH - bottom - cardH, g)
        if (!open) {
            Box(
                Modifier.offset {
                        IntOffset(currentPos.x.roundToInt(), currentPos.y.roundToInt())
                    }
                    .size(FAB_DP.dp)
                    .onGloballyPositioned { boxCoords = it }
                    .focusRequester(fabFocus)
                    .semantics {
                        contentDescription = notesLabel
                        stateDescription = "$collapsedState. $hintText"
                        customActions =
                            listOf(
                                CustomAccessibilityAction(moveUpLabel) {
                                    settle(NotesFabMath.step(placement, -1), fromDrag = false)
                                    true
                                },
                                CustomAccessibilityAction(moveDownLabel) {
                                    settle(NotesFabMath.step(placement, +1), fromDrag = false)
                                    true
                                },
                                CustomAccessibilityAction(moveSideLabel) {
                                    settle(NotesFabMath.flipSide(placement), fromDrag = false)
                                    true
                                },
                            )
                    }
                    .clickable(role = Role.Button, onClickLabel = notesLabel) { open = true }
                    .pointerInput(Unit) {
                        // Long-press picks the note up; from then on every change is consumed, so
                        // the finished drag is no tap and nothing else scrolls.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            val rc = rootCoords
                            val bc = boxCoords
                            if (rc == null || bc == null) return@awaitEachGesture
                            onPickUp()
                            val grab = bc.positionInRootOf(rc, down.position)
                            // The finger's offset inside the note stays fixed while it is carried.
                            val inside = down.position
                            var prevX = grab.x
                            var prevT = down.uptimeMillis
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                val finger = bc.positionInRootOf(rc, change.position)
                                val dtMs = (change.uptimeMillis - prevT).coerceAtLeast(1L)
                                val vx = (finger.x - prevX) / dtMs * 1000f / this.density
                                prevX = finger.x
                                prevT = change.uptimeMillis
                                onDragTo(
                                    Offset(finger.x - inside.x, finger.y - inside.y),
                                    vx,
                                    change.uptimeMillis,
                                )
                            }
                            onDrop()
                        }
                    }
                    .testTag("notes-fab")
            )
        } else {
            val pinCy = top + with(density) { CARD_PIN_DP.dp.toPx() / 2f }
            Box(
                Modifier.offset {
                        IntOffset(
                            (cx - 24.dp.toPx()).roundToInt(),
                            (pinCy - 24.dp.toPx()).roundToInt(),
                        )
                    }
                    .size(48.dp)
                    .focusRequester(closeFocus)
                    .semantics {
                        contentDescription = closeLabel
                        stateDescription = expandedState
                    }
                    .clickable(role = Role.Button, onClickLabel = closeLabel) { open = false }
            )
            Box(
                Modifier.offset {
                        IntOffset(
                            (cx + cardW / 2f - 48.dp.toPx() - 4.dp.toPx()).roundToInt(),
                            (top + 4.dp.toPx()).roundToInt(),
                        )
                    }
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = closeLabel) { open = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = closeLabel,
                    tint = ink.copy(alpha = 0.55f),
                )
            }
        }
    }
}
