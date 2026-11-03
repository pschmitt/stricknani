package blue.anika.wolle.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.animationsDisabled
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

private val YARN_COLORS =
    listOf(
        Color(0xFFFF6F9F),
        Color(0xFFFF8FB4),
        Color(0xFF8CB8FF),
        Color(0xFFFFD36B),
        Color(0xFF7BE0B0),
    )
private const val HEART_PATH =
    "M20.8 8.8c0 5.7-8.8 10.3-8.8 10.3S3.2 14.5 3.2 8.8a4.6 4.6 0 0 1 8.8-2.1 4.6 4.6 0 0 1 8.8 2.1Z"
private const val PUFF_MS = 800

/**
 * Wraps a Home card so it can be dismissed with a swipe (either direction) or from inside via the
 * `requestDismiss` passed to [content]. The card follows the finger, tilting and fading; past 38%
 * of its width, or on a fling, it squishes, tilts and flies off with a puff of hearts and yarn,
 * then the room it took (including its [gap] below) collapses so the content underneath glides up.
 * Otherwise it springs back with a little overshoot. With animations off the exit is a quick fade
 * and collapse. [onDismissed] runs when the exit is over: that is where to remember the dismissal
 * and drop the card, since dropping it earlier would cut the animation short.
 */
@Composable
fun DismissibleCard(
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = 16.dp,
    content: @Composable (requestDismiss: () -> Unit) -> Unit,
) {
    val reduced = animationsDisabled()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val dragX = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val squish = remember { Animatable(0f) }
    val shrink = remember { Animatable(1f) }
    val collapse = remember { Animatable(1f) }
    val puff = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    var direction by remember { mutableFloatStateOf(1f) }
    val bits = remember { dismissParticles(rand = { Math.random().toFloat() }) }
    val heart = remember { PathParser().parsePathString(HEART_PATH).toPath() }

    fun leave(toward: Int) {
        if (leaving) return
        leaving = true
        direction = toward.toFloat()
        scope.launch {
            dragX.stop()
            if (reduced) {
                fade.animateTo(0f, tween(140, easing = LinearEasing))
                collapse.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
            } else {
                // Anticipation: a quick squish, then fling it off and puff.
                squish.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh))
                launch { puff.animateTo(1f, tween(PUFF_MS, easing = LinearEasing)) }
                listOf(
                        async {
                            dragX.animateTo(
                                toward * width * 1.3f,
                                tween(380, easing = FastOutLinearInEasing),
                            )
                        },
                        async { fade.animateTo(0f, tween(380, easing = FastOutLinearInEasing)) },
                        async { shrink.animateTo(0.9f, tween(380)) },
                    )
                    .awaitAll()
                collapse.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            }
            onDismissed()
        }
    }

    val dragState = rememberDraggableState { delta ->
        scope.launch { dragX.snapTo(dragX.value + delta) }
    }
    val dismissLabel = stringResource(R.string.sync_issue_dismiss)

    Box(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * collapse.value).roundToInt()
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .padding(bottom = gap)
    ) {
        Box(
            Modifier.onSizeChanged { width = it.width.toFloat() }
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(dismissLabel) {
                                leave(1)
                                true
                            }
                        )
                }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = !leaving,
                    onDragStopped = { velocity ->
                        when (val outcome = swipeOutcome(dragX.value, width, velocity, density)) {
                            is SwipeOutcome.Dismiss -> leave(outcome.direction)
                            SwipeOutcome.Restore ->
                                scope.launch {
                                    // A bouncy return, like the rest of the app's springs.
                                    if (reduced) {
                                        dragX.animateTo(0f, tween(120))
                                    } else {
                                        dragX.animateTo(
                                            0f,
                                            spring(
                                                Spring.DampingRatioMediumBouncy,
                                                Spring.StiffnessMedium,
                                            ),
                                        )
                                    }
                                }
                        }
                    },
                )
                .graphicsLayer {
                    val x = dragX.value
                    translationX = x
                    rotationZ = dragRotation(x, width) - direction * 3f * squish.value * fade.value
                    alpha = dragAlpha(x, width).coerceAtMost(1f) * fade.value.coerceIn(0f, 1f)
                    scaleX = (1f + 0.05f * squish.value) * shrink.value
                    scaleY = (1f - 0.07f * squish.value) * shrink.value
                }
        ) {
            content { leave(1) }
        }
        // The puff stays put while the card flies off.
        if (puff.value > 0f && puff.value < 1f) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val t = puff.value
                    bits.forEach { bit ->
                        val local =
                            ((t - bit.delayFraction) / (1f - bit.delayFraction)).coerceIn(0f, 1f)
                        if (local <= 0f) return@forEach
                        val eased = 1f - (1f - local) * (1f - local)
                        val alpha = if (local < 0.2f) local / 0.2f else 1f - (local - 0.2f) / 0.8f
                        val center =
                            Offset(
                                size.width / 2f + bit.dx * density * eased,
                                size.height / 2f + bit.dy * density * eased,
                            )
                        val radius = bit.size * density * (0.3f + 0.7f * eased)
                        val color =
                            YARN_COLORS[bit.colorIndex % YARN_COLORS.size].copy(alpha = alpha)
                        if (bit.heart) {
                            withTransform({
                                translate(center.x - radius, center.y - radius)
                                scale(radius * 2f / 24f, radius * 2f / 24f, Offset.Zero)
                            }) {
                                drawPath(heart, color)
                            }
                        } else {
                            drawCircle(color, radius, center)
                        }
                    }
                }
            )
        }
    }
}
