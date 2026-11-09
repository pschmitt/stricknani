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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import blue.anika.wolle.ui.theme.StickyPaperDark
import blue.anika.wolle.ui.theme.StickyPaperFoldDark
import blue.anika.wolle.ui.theme.StickyPaperFoldLight
import blue.anika.wolle.ui.theme.StickyPaperInkDark
import blue.anika.wolle.ui.theme.StickyPaperInkLight
import blue.anika.wolle.ui.theme.StickyPaperLight

internal const val TILT_DEGREES = -1.5f
private val FoldSize = 22.dp
internal val CornerRadius = 14.dp

/** A rounded rectangle whose bottom-right corner is cut off diagonally, like a folded page. */
internal class FoldedCornerShape(private val fold: Float, private val radius: Float) : Shape {
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
 * The shell of a sticky note: warm paper, a slight tilt, a soft lifted shadow, a push pin through
 * the top edge and a folded bottom-right corner. Pressing it gives a small squish, skipped when
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
        // A push pin through the top edge: a glossy round head in the accent colour with a soft
        // drop shadow. It sits in the tilted box, so it turns with the paper.
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 1.dp).size(PinSize).drawBehind {
                drawPin(tape, Offset(size.width / 2f, size.width / 2f), size.width / 2f)
            }
        )
    }
}

private val PinSize = 21.dp

/** A glossy push-pin head in [tape] at [center], with a tight and a soft drop shadow. */
internal fun DrawScope.drawPin(tape: Color, center: Offset, r: Float) {
    val shadow = Color.Black
    drawCircle(shadow.copy(alpha = 0.35f), r, center + Offset(1.dp.toPx(), 1.dp.toPx()))
    val soft = center + Offset(3.dp.toPx(), 5.dp.toPx())
    drawCircle(
        brush =
            Brush.radialGradient(
                listOf(shadow.copy(alpha = 0.28f), shadow.copy(alpha = 0f)),
                soft,
                r * 1.5f,
            ),
        radius = r * 1.5f,
        center = soft,
    )
    drawCircle(
        brush = Brush.radialGradient(listOf(tape, tape.darkened(0.65f)), center, r),
        radius = r,
        center = center,
    )
    val glint = center + Offset(-r * 0.32f, -r * 0.40f)
    drawCircle(
        brush =
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.75f),
                0.32f to Color.Transparent,
                1f to Color.Transparent,
                center = glint,
                radius = r,
            ),
        radius = r,
        center = center,
    )
}

/** [this] with its colour channels scaled by [factor] (alpha kept). */
private fun Color.darkened(factor: Float) =
    Color(red * factor, green * factor, blue * factor, alpha)

/**
 * The editable sticky note: the same paper, pin, fold and tilt as [StickyNoteCard] around one
 * labelled text field (the label is the field's accessible name). Ink-coloured text and outline
 * keep the contrast of the paper in both themes.
 */
@Composable
internal fun StickyNoteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    minLines: Int = 4,
    keyboardOptions: KeyboardOptions =
        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    labelIcon: ImageVector? = null,
) {
    StickyNoteSurface(modifier) {
        val ink = LocalContentColor.current
        MarkdownTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            labelIcon = labelIcon,
            fieldModifier = fieldModifier.testTag("sticky-note-field"),
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ink,
                    unfocusedTextColor = ink,
                    focusedBorderColor = ink,
                    unfocusedBorderColor = ink.copy(alpha = 0.55f),
                    focusedLabelColor = ink,
                    unfocusedLabelColor = ink.copy(alpha = 0.8f),
                    cursorColor = ink,
                ),
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
