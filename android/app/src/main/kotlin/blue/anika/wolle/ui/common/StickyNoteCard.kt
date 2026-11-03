package blue.anika.wolle.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import blue.anika.wolle.ui.theme.StickyPaperDark
import blue.anika.wolle.ui.theme.StickyPaperFoldDark
import blue.anika.wolle.ui.theme.StickyPaperFoldLight
import blue.anika.wolle.ui.theme.StickyPaperInkDark
import blue.anika.wolle.ui.theme.StickyPaperInkLight
import blue.anika.wolle.ui.theme.StickyPaperLight

private const val TILT_DEGREES = -1.5f
private val FoldSize = 22.dp
private val CornerRadius = 14.dp

/** A rounded rectangle whose bottom-right corner is cut off diagonally, like a folded page. */
private class FoldedCornerShape(private val fold: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Generic(
            Path().apply {
                val w = size.width
                val h = size.height
                val d = radius * 2
                moveTo(radius, 0f)
                lineTo(w - radius, 0f)
                arcTo(Rect(w - d, 0f, w, d), -90f, 90f, false)
                lineTo(w, h - fold)
                lineTo(w - fold, h)
                lineTo(radius, h)
                arcTo(Rect(0f, h - d, d, h), 90f, 90f, false)
                lineTo(0f, radius)
                arcTo(Rect(0f, 0f, d, d), 180f, 90f, false)
                close()
            }
        )
}

/**
 * The shell of a sticky note: warm paper, a slight tilt, a soft lifted shadow, a washi-tape strip
 * on the top edge and a folded bottom-right corner. Pressing it gives a small squish, skipped when
 * animations are off; the tilt itself is static. The press is only observed, never consumed, so
 * long-press menus and scrolling on the content keep working.
 */
@Composable
internal fun StickyNoteSurface(
    modifier: Modifier = Modifier,
    tape: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val paper = if (isLight) StickyPaperLight else StickyPaperDark
    val ink = if (isLight) StickyPaperInkLight else StickyPaperInkDark
    val fold = if (isLight) StickyPaperFoldLight else StickyPaperFoldDark
    val still = animationsDisabled()
    var pressed by remember { mutableStateOf(false) }
    val scale by
        animateFloatAsState(
            targetValue = if (pressed && !still) 0.975f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
            label = "sticky-note-squish",
        )
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .graphicsLayer {
                rotationZ = TILT_DEGREES
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    pressed = false
                }
            }
            .testTag("notes-card")
    ) {
        Surface(
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            shape =
                with(androidx.compose.ui.platform.LocalDensity.current) {
                    FoldedCornerShape(FoldSize.toPx(), CornerRadius.toPx())
                },
            color = paper,
            contentColor = ink,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            val f = FoldSize.toPx()
                            val flap =
                                Path().apply {
                                    moveTo(size.width - f, size.height)
                                    lineTo(size.width, size.height - f)
                                    lineTo(size.width - f, size.height - f)
                                    close()
                                }
                            drawPath(flap, fold)
                        }
                        .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
        // Washi tape across the top edge: translucent, a hair off-level, with a stitched seam.
        Box(
            Modifier.align(Alignment.TopCenter)
                .size(width = 76.dp, height = 22.dp)
                .graphicsLayer { rotationZ = 2.5f }
                .drawBehind {
                    drawRoundRect(
                        tape.copy(alpha = 0.55f),
                        cornerRadius =
                            androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    )
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    for (y in listOf(5.dp.toPx(), size.height - 5.dp.toPx())) {
                        drawLine(
                            Color.White.copy(alpha = 0.6f),
                            Offset(4.dp.toPx(), y),
                            Offset(size.width - 4.dp.toPx(), y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dash,
                        )
                    }
                }
        )
    }
}

/** A titled sticky note; the title is a heading in the display face. */
@Composable
internal fun StickyNoteCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    StickyNoteSurface(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** The "no notes yet" state: a faint dashed-outline note that opens the editor on tap. */
@Composable
internal fun StickyNoteEmpty(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .graphicsLayer { rotationZ = TILT_DEGREES }
            .drawBehind {
                drawRoundRect(
                    outline.copy(alpha = 0.7f),
                    cornerRadius =
                        androidx.compose.ui.geometry.CornerRadius(
                            CornerRadius.toPx(),
                            CornerRadius.toPx(),
                        ),
                    style =
                        Stroke(
                            width = 2.dp.toPx(),
                            pathEffect =
                                PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())),
                        ),
                )
            }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 20.dp)
            .testTag("notes-empty")
    ) {
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
