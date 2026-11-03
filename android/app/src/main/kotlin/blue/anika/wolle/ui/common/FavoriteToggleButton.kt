package blue.anika.wolle.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val BURST_MILLIS = 500
private const val DRIFT_MILLIS = 650

/**
 * Heart toggle shared by the project and yarn screens. Marking a favourite pops the heart with a
 * springy overshoot and sends a small burst of hearts outwards; unmarking deflates it with a tiny
 * wobble and lets one heart drift down. Both play only for a tap on this button, never for the
 * first composition or a list refresh, and a haptic tick goes with them. With animations turned off
 * (see [animationsDisabled]) the heart only swaps state and fades its colour.
 */
@Composable
fun FavoriteToggleButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val still = animationsDisabled()
    val haptics = LocalHapticFeedback.current
    val tint by
        animateColorAsState(
            targetValue =
                if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(durationMillis = 180),
            label = "favorite-tint",
        )
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    // Set by a tap here; the effect below consumes it once the state really flips.
    var requested by remember { mutableStateOf<Boolean?>(null) }
    var drifting by remember { mutableStateOf(false) }
    val particles = remember { burstParticles() }
    val heart = rememberVectorPainter(Icons.Filled.Favorite)
    val burstColor = MaterialTheme.colorScheme.primary
    val driftColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(isFavorite) {
        val wanted = requested
        requested = null
        if (wanted != isFavorite) return@LaunchedEffect
        haptics.performHapticFeedback(
            if (isFavorite) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
        )
        if (still) return@LaunchedEffect
        coroutineScope {
            if (isFavorite) {
                drifting = false
                launch {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(BURST_MILLIS, easing = LinearEasing))
                }
                scale.snapTo(0.7f)
                scale.animateTo(1.35f, tween(150, easing = FastOutSlowInEasing))
                scale.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            } else {
                burst.snapTo(0f)
                drifting = true
                launch {
                    burst.animateTo(1f, tween(DRIFT_MILLIS, easing = LinearEasing))
                    drifting = false
                }
                launch {
                    for (target in listOf(-8f, 6f, -2f, 0f)) {
                        rotation.animateTo(target, tween(100))
                    }
                }
                scale.animateTo(0.8f, tween(110, easing = FastOutSlowInEasing))
                scale.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
        }
    }

    IconButton(
        onClick = {
            requested = !isFavorite
            onClick()
        },
        modifier =
            modifier.drawBehind {
                val progress = burst.value
                if (progress <= 0f || progress >= 1f) return@drawBehind
                val centre = center
                if (isFavorite) {
                    particles.forEach { particle ->
                        val local =
                            ((progress - particle.delayFraction) / (1f - particle.delayFraction))
                                .coerceIn(0f, 1f)
                        if (local <= 0f) return@forEach
                        val sizePx = particle.size.dp.toPx() * (0.4f + 0.6f * local)
                        val x = centre.x + particle.dx.dp.toPx() * local - sizePx / 2
                        val y = centre.y + particle.dy.dp.toPx() * local - sizePx / 2
                        drawHeart(
                            heart,
                            x,
                            y,
                            sizePx,
                            burstColor.copy(alpha = 1f - local),
                        )
                    }
                } else if (drifting) {
                    val sizePx = 9.dp.toPx() * (1f - 0.4f * progress)
                    drawHeart(
                        heart,
                        centre.x - sizePx / 2,
                        centre.y + 26.dp.toPx() * progress - sizePx / 2,
                        sizePx,
                        driftColor.copy(alpha = 0.8f * (1f - progress)),
                    )
                }
            },
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription =
                stringResource(
                    if (isFavorite) R.string.common_unfavorite else R.string.common_favorite
                ),
            tint = tint,
            modifier =
                Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    rotationZ = rotation.value
                },
        )
    }
}

private fun DrawScope.drawHeart(
    painter: Painter,
    x: Float,
    y: Float,
    sizePx: Float,
    color: Color,
) {
    translate(x, y) {
        with(painter) {
            draw(
                size = Size(sizePx, sizePx),
                colorFilter = ColorFilter.tint(color),
            )
        }
    }
}
