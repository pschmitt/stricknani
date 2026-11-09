@file:OptIn(ExperimentalRichTextApi::class)

package blue.anika.wolle.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.trigger.Trigger
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * Thumbnails of the pictures in the visual editor, the counterpart of the web editor's inline
 * pictures. The library's editable text cannot show inline content, so a picture that is alone in
 * its paragraph is one transparent character in a paragraph with a tall line (see
 * [MarkdownRich.relayoutPictures]) and the picture is drawn behind that line from the text layout:
 * it needs no position bookkeeping and follows layout changes and scrolling by itself.
 */
internal object ThumbnailGeometry {
    /** Height of the thumbnail line, in dp. */
    const val LINE_HEIGHT_DP = 160

    /** The reader's width for a picture without a `{.sn-size-*}` annotation. */
    private const val DEFAULT_FRACTION = 0.55f

    /**
     * The size of the picture box in a line [lineHeight] tall with [availableWidth] to its right:
     * the picture scaled to fit (keeping its aspect), at most the annotated fraction of the width.
     * Without [intrinsic] (loading, offline, failed) it is a 4:3 placeholder box.
     */
    fun fit(
        availableWidth: Float,
        lineHeight: Float,
        padding: Float,
        intrinsic: Size?,
        size: MarkdownImageSize?,
    ): Size {
        val maxH = (lineHeight - 2 * padding).coerceAtLeast(0f)
        val maxW = (availableWidth * (size?.widthFraction ?: DEFAULT_FRACTION)).coerceAtLeast(0f)
        if (intrinsic == null || intrinsic.width <= 0f || intrinsic.height <= 0f) {
            return Size(minOf(maxW, maxH * 4f / 3f), maxH)
        }
        val scale = minOf(maxW / intrinsic.width, maxH / intrinsic.height)
        return Size(intrinsic.width * scale, intrinsic.height * scale)
    }

    /** True when [selection] has the thumbnail at [offset] in it or its caret is next to it. */
    fun touches(selection: TextRange, offset: Int): Boolean =
        selection.min <= offset + 1 && selection.max >= offset

    /** True when a collapsed caret sits right before or after the thumbnail at [offset]. */
    fun caretBeside(selection: TextRange, offset: Int): Boolean =
        selection.collapsed && (selection.start == offset || selection.start == offset + 1)

    /** The selection that covers exactly the thumbnail at [offset]. */
    fun selectionOf(offset: Int): TextRange = TextRange(offset, offset + 1)
}

/**
 * Register the two picture token kinds: the chip keeps the link colour, the thumbnail is hidden.
 */
internal fun registerPictureTriggers(state: RichTextState) {
    state.registerTrigger(
        Trigger(
            id = EditorImage.TRIGGER,
            char = EditorImage.TRIGGER_CHAR,
            style = { SpanStyle(color = it.linkColor) },
        )
    )
    state.registerTrigger(
        Trigger(
            id = EditorImage.THUMB_TRIGGER,
            char = EditorImage.THUMB_TRIGGER_CHAR,
            style = { SpanStyle(color = Color.Transparent) },
        )
    )
}

/** A loaded picture for the draw pass. */
internal class ThumbnailImage(val bitmap: ImageBitmap) {
    /** The size of the decoded picture. */
    val intrinsic: Size
        get() = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
}

/**
 * The loaded pictures of [images], fetched through the app's authenticated image loader (Coil's
 * singleton, the one the reader uses) with the URL resolved like the reader does. A picture that is
 * unsafe, still loading, offline or failed is not in the map and is drawn as a placeholder.
 */
@Composable
internal fun rememberThumbnailPainters(images: List<EditorImage>): Map<String, ThumbnailImage> {
    val result = remember { mutableStateMapOf<String, ThumbnailImage>() }
    val resolve = LocalOwnerContext.current.resolveUrl
    val context = LocalContext.current
    for (image in images.distinctBy { it.markdown }) {
        key(image.markdown) {
            val url = image.resolvedSource(resolve)
            LaunchedEffect(url) {
                if (url == null) return@LaunchedEffect
                val request =
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(LOAD_SIZE)
                        .allowHardware(false)
                        .build()
                val loaded =
                    try {
                        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                            ?.image
                            ?.toBitmap()
                            ?.asImageBitmap()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                if (loaded != null) result[image.markdown] = ThumbnailImage(loaded)
            }
        }
    }
    return result
}

private const val LOAD_SIZE = 1024

/** Where a thumbnail is drawn, in the coordinates of the editor. */
internal data class ThumbnailPlacement(val offset: Int, val image: EditorImage, val rect: Rect)

/** What the draw and tap passes of the editor read; every value is Compose state. */
@Stable
internal class ThumbnailHost(val state: RichTextState) {
    var layout: TextLayoutResult? by mutableStateOf<TextLayoutResult?>(null)
    var pictures: List<MarkdownRich.EditorPicture> by
        mutableStateOf(emptyList<MarkdownRich.EditorPicture>())
    var images: Map<String, ThumbnailImage> = emptyMap()
    var paddingPx: Float = 0f

    /** The outline of the selected thumbnail shows only while the editor has the focus. */
    var focused: Boolean by mutableStateOf(false)

    /** True when a collapsed caret is next to a thumbnail, whose tall caret is then hidden. */
    fun caretBesidePicture(): Boolean = pictures.any {
        ThumbnailGeometry.caretBeside(state.selection, it.offset)
    }

    fun placements(): List<ThumbnailPlacement> {
        val layout = layout ?: return emptyList()
        val text = state.annotatedString.text
        // A layout of older text: nothing to draw until the new one arrives.
        if (layout.layoutInput.text.length != text.length) return emptyList()
        val width = layout.size.width.toFloat()
        return pictures.mapNotNull { picture ->
            if (picture.offset !in text.indices || text[picture.offset] != THUMB_CHAR)
                return@mapNotNull null
            val line = layout.getLineForOffset(picture.offset)
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            val left = layout.getHorizontalPosition(picture.offset, true)
            val size =
                ThumbnailGeometry.fit(
                    availableWidth = (width - left).coerceAtLeast(0f),
                    lineHeight = bottom - top,
                    padding = paddingPx,
                    intrinsic = images[picture.image.markdown]?.intrinsic,
                    size = picture.image.size,
                )
            ThumbnailPlacement(
                picture.offset,
                picture.image,
                Rect(Offset(left, top + paddingPx), size),
            )
        }
    }

    private companion object {
        val THUMB_CHAR = EditorImage.THUMB_LABEL[0]
    }
}

/**
 * Draws the thumbnails behind the text of the editor and selects one when it is tapped (so
 * Backspace or Delete removes it as a unit). Taps are observed, never consumed: the editor still
 * gets focus and places its caret, and the selection of the picture is set after that.
 */
internal fun Modifier.pictureThumbnails(
    host: ThumbnailHost,
    measurer: TextMeasurer,
    placeholder: Color,
    onPlaceholder: Color,
    outline: Color,
    corner: Dp = 12.dp,
    textStyle: TextStyle = TextStyle.Default,
    onTap: (Int) -> Unit,
): Modifier =
    this.pointerInput(host) {
            awaitEachGesture {
                val down =
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up =
                    waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                val moved = (up.position - down.position).getDistance()
                val duration = up.uptimeMillis - down.uptimeMillis
                if (
                    moved > viewConfiguration.touchSlop ||
                        duration >= viewConfiguration.longPressTimeoutMillis
                ) {
                    return@awaitEachGesture
                }
                host
                    .placements()
                    .firstOrNull { it.rect.contains(up.position) }
                    ?.let { onTap(it.offset) }
            }
        }
        .drawBehind {
            val radius = CornerRadius(corner.toPx())
            val strokeWidth = 2.dp.toPx()
            val selection = host.state.selection
            for (placement in host.placements()) {
                val rect = placement.rect
                if (rect.width <= 0f || rect.height <= 0f) continue
                val image = host.images[placement.image.markdown]
                val shape = Path().apply { addRoundRect(RoundRect(rect, radius)) }
                if (image != null) {
                    clipPath(shape) {
                        drawImage(
                            image.bitmap,
                            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
                if (image == null) {
                    drawRoundRect(placeholder, rect.topLeft, rect.size, radius)
                    val pad = 8.dp.toPx()
                    val room =
                        Size(
                            (rect.width - 2 * pad).coerceAtLeast(0f),
                            (rect.height - 2 * pad).coerceAtLeast(0f),
                        )
                    if (room.width > 0f && room.height > 0f) {
                        val alt = placement.image.alt.ifBlank { placement.image.chip() }
                        val laidOut =
                            measurer.measure(
                                alt,
                                style = textStyle.copy(color = onPlaceholder),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                constraints = Constraints(maxWidth = room.width.toInt()),
                            )
                        drawText(laidOut, topLeft = rect.topLeft + Offset(pad, pad))
                    }
                }
                if (host.focused && ThumbnailGeometry.touches(selection, placement.offset)) {
                    drawRoundRect(
                        outline,
                        rect.topLeft,
                        rect.size,
                        radius,
                        style = Stroke(strokeWidth),
                    )
                }
            }
        }
