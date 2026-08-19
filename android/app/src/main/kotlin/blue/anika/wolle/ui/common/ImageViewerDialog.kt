package blue.anika.wolle.ui.common

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import blue.anika.wolle.R
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlin.math.abs
import kotlinx.coroutines.launch

private val DismissThreshold = 120.dp
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

/** An image shown in [ImageViewerDialog], with context for the current detail screen. */
data class ImageViewerImage(
    val url: String,
    val title: String,
    val sourceLabel: String,
    val altText: String? = null,
)

internal data class ImageDimensions(val width: Int, val height: Int)

private fun targetIndex(currentPage: Int, pageCount: Int, step: Int): Int? {
    if (pageCount <= 0 || step == 0) return null
    return (currentPage + step).takeIf { it in 0 until pageCount }
}

/**
 * Full-screen swipe-to-dismiss image viewer (SNA-23) - pinch-to-zoom/pan on the current image,
 * horizontal swipe between [images] via [HorizontalPager], vertical drag-down dismisses (an
 * explicit close button is also shown for discoverability). Shown as a plain [Dialog], not a
 * navigation route. Ported from nyetbox's `ImageViewerDialog` (no third-party zoom/pager dependency
 * needed - `HorizontalPager` is core Compose Foundation).
 */
@Composable
fun ImageViewerDialog(images: List<ImageViewerImage>, initialIndex: Int, onDismiss: () -> Unit) {
    if (images.isEmpty()) return
    val pagerState =
        rememberPagerState(initialPage = initialIndex.coerceIn(0, images.lastIndex)) {
            images.size
        }
    val dismissOffsetY = remember { Animatable(0f) }
    var isZoomed by remember { mutableStateOf(false) }
    var loadedDimensions by remember { mutableStateOf<Map<String, ImageDimensions>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thresholdPx = with(density) { DismissThreshold.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dismissProgress = (abs(dismissOffsetY.value) / (thresholdPx * 3)).coerceIn(0f, 1f)
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.7f))
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            translationY = dismissOffsetY.value
                            alpha = 1f - dismissProgress * 0.5f
                        }
                        .pointerInput(isZoomed) {
                            if (isZoomed) return@pointerInput
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (abs(dismissOffsetY.value) > thresholdPx) {
                                        onDismiss()
                                    } else {
                                        scope.launch { dismissOffsetY.animateTo(0f) }
                                    }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        dismissOffsetY.snapTo(dismissOffsetY.value + dragAmount)
                                    }
                                },
                            )
                        }
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isZoomed,
                ) { page ->
                    ZoomableImagePage(
                        image = images[page],
                        page = page,
                        pageCount = images.size,
                        onZoomChanged = { zoomed ->
                            if (page == pagerState.currentPage) isZoomed = zoomed
                        },
                        onDimensionsLoaded = { url, dimensions ->
                            if (dimensions != null) {
                                loadedDimensions = loadedDimensions + (url to dimensions)
                            }
                        },
                    )
                }
            }
            val currentPage = pagerState.currentPage.coerceIn(0, images.lastIndex)
            if (images.size > 1) {
                val previousPage = targetIndex(currentPage, images.size, -1)
                val nextPage = targetIndex(currentPage, images.size, 1)
                IconButton(
                    onClick = {
                        previousPage?.let { target ->
                            scope.launch { pagerState.animateScrollToPage(target) }
                        }
                    },
                    enabled = previousPage != null,
                    modifier =
                        Modifier.align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = stringResource(R.string.image_viewer_previous_image),
                        tint = Color.White.copy(alpha = if (previousPage == null) 0.35f else 1f),
                    )
                }
                IconButton(
                    onClick = {
                        nextPage?.let { target ->
                            scope.launch { pagerState.animateScrollToPage(target) }
                        }
                    },
                    enabled = nextPage != null,
                    modifier =
                        Modifier.align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.image_viewer_next_image),
                        tint = Color.White.copy(alpha = if (nextPage == null) 0.35f else 1f),
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Color.White,
                )
            }
            ImageMetadataPanel(
                image = images[currentPage],
                dimensions = loadedDimensions[images[currentPage].url],
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Pinch-to-zoom + pan-while-zoomed on a single page; leaves single-finger drags unconsumed while at
 * 1x scale so the enclosing [HorizontalPager] (page swipe) and the dismiss-drag [Box] above still
 * receive them.
 */
@Composable
private fun ZoomableImagePage(
    image: ImageViewerImage,
    page: Int,
    pageCount: Int,
    onZoomChanged: (Boolean) -> Unit,
    onDimensionsLoaded: (String, ImageDimensions?) -> Unit,
) {
    var scale by remember(image.url) { mutableFloatStateOf(1f) }
    var offset by remember(image.url) { mutableStateOf(Offset.Zero) }
    val painter = rememberAsyncImagePainter(model = image.url)
    val painterState by painter.state.collectAsState()
    val dimensions =
        (painterState as? AsyncImagePainter.State.Success)?.result?.image?.let {
            ImageDimensions(width = it.width, height = it.height)
        }
    val accessibilityDescription =
        stringResource(
            R.string.image_viewer_image_description,
            image.title,
            image.sourceLabel,
            page + 1,
            pageCount,
        )

    LaunchedEffect(image.url, dimensions) { onDimensionsLoaded(image.url, dimensions) }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .pointerInput(image.url) {
                detectZoomPan(
                    isZoomed = { scale > MIN_SCALE },
                    onGesture = { pan, zoom ->
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        val maxOffsetX = (size.width * (newScale - 1)) / 2f
                        val maxOffsetY = (size.height * (newScale - 1)) / 2f
                        offset =
                            Offset(
                                x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY),
                            )
                        scale = newScale
                        onZoomChanged(newScale > MIN_SCALE)
                    },
                    onDoubleTap = {
                        scale = if (scale > MIN_SCALE) MIN_SCALE else 2.5f
                        offset = Offset.Zero
                        onZoomChanged(scale > MIN_SCALE)
                    },
                )
                }
                .semantics {
                    contentDescription = accessibilityDescription
                },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Composable
private fun ImageMetadataPanel(
    image: ImageViewerImage,
    dimensions: ImageDimensions?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier.fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = image.title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        imageMetadataRows(image, dimensions).forEach { (label, value) ->
            val localizedLabel =
                stringResource(
                    when (label) {
                        "context" -> R.string.image_viewer_context_label
                        "description" -> R.string.image_viewer_description_label
                        else -> R.string.image_viewer_dimensions_label
                    }
                )
            Row(modifier = Modifier.padding(top = 3.dp)) {
                Text(
                    text = "$localizedLabel: ",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = value,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun imageMetadataRows(
    image: ImageViewerImage,
    dimensions: ImageDimensions?,
): List<Pair<String, String>> =
    buildList {
        add("context" to image.sourceLabel)
        image.altText?.takeIf(String::isNotBlank)?.let { add("description" to it) }
        dimensions?.let { add("dimensions" to "${it.width} × ${it.height} px") }
    }

/**
 * Combined pinch-zoom + pan gesture detector that only consumes pointer events while actually
 * zoomed (or mid-pinch) - a plain single-finger drag at 1x scale is left unconsumed so it bubbles
 * up to the pager's page-swipe and the dismiss-drag detector instead of being swallowed here.
 */
private suspend fun PointerInputScope.detectZoomPan(
    isZoomed: () -> Boolean,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
    onDoubleTap: () -> Unit,
) {
    var lastTapAt = 0L
    var lastTapPosition = Offset.Unspecified
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var multiTouch = false
        do {
            val event = awaitPointerEvent()
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            multiTouch = multiTouch || event.changes.size > 1
            moved =
                moved ||
                    (event.changes.firstOrNull()?.position?.minus(firstDown.position)?.getDistance()
                        ?: 0f) > viewConfiguration.touchSlop
            if (event.changes.size > 1 || zoomChange != 1f || isZoomed()) {
                onGesture(panChange, zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        val now = SystemClock.uptimeMillis()
        val tapPosition = firstDown.position
        if (!moved && !multiTouch) {
            val isSecondTap =
                lastTapAt != 0L &&
                    now - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
                    (tapPosition - lastTapPosition).getDistance() <= viewConfiguration.touchSlop * 2
            if (isSecondTap) {
                onDoubleTap()
                lastTapAt = 0L
            } else {
                lastTapAt = now
                lastTapPosition = tapPosition
            }
        } else {
            lastTapAt = 0L
        }
    }
}
