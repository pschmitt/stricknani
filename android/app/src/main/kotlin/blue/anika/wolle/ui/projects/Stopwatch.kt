package blue.anika.wolle.ui.projects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.animationsDisabled

/**
 * The project timer's analog stopwatch, the same face as the web card: a crown that stays pressed
 * while the timer runs, a knitting-needle second hand that sweeps between ticks, a minute subdial,
 * a breathing halo and one wiggle on start. Decorative only; the digits beside it carry the time.
 * With animations turned off the hands jump and nothing breathes or wiggles.
 */
@Composable
internal fun Stopwatch(
    totalSeconds: Long,
    running: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    mini: Boolean = false,
) {
    val still = animationsDisabled()
    // Cumulative degrees, so passing a full minute keeps sweeping forward instead of back.
    val secondAngle by
        animateFloatAsState(
            targetValue = totalSeconds * 6f,
            animationSpec =
                when {
                    still -> snap()
                    running -> tween(durationMillis = 1000, easing = LinearEasing)
                    else -> spring(dampingRatio = 0.55f, stiffness = 120f)
                },
            label = "stopwatch-second",
        )
    val minuteAngle by
        animateFloatAsState(
            targetValue = (totalSeconds % 3600) / 10f,
            animationSpec = if (still) snap() else tween(1000, easing = LinearEasing),
            label = "stopwatch-minute",
        )
    val crownPress by
        animateFloatAsState(
            targetValue = if (running) 3f else 0f,
            animationSpec = if (still) snap() else tween(160, easing = FastOutSlowInEasing),
            label = "stopwatch-crown",
        )
    val halo =
        if (running && !still && !mini) {
            val transition = rememberInfiniteTransition(label = "stopwatch-halo")
            transition
                .animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            tween(1200, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse,
                        ),
                    label = "stopwatch-halo-phase",
                )
                .value
        } else null
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(running, still) {
        if (running && !still) {
            wiggle.snapTo(0f)
            wiggle.animateTo(
                0f,
                keyframes {
                    durationMillis = 520
                    -7f at 130
                    5f at 290
                    -2f at 420
                },
            )
        }
    }

    val case = MaterialTheme.colorScheme.primary
    val dial = MaterialTheme.colorScheme.surface
    val wash = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier.size(width = size, height = size * 1.12f).graphicsLayer {
            rotationZ = wiggle.value
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.6f)
        }
    ) {
        // Draw in the web SVG's 100 x 112 units.
        scale(this.size.width / 100f, pivot = Offset.Zero) {
            val center = Offset(50f, 62f)
            if (halo != null) {
                scale(0.94f + 0.1f * halo, pivot = center) {
                    drawCircle(wash, radius = 46f, center = center, alpha = 0.25f + 0.65f * halo)
                }
            }
            translate(top = crownPress) {
                drawRoundRect(case, Offset(46.5f, 8f), Size(7f, 8f), CornerRadius(1.5f))
                drawRoundRect(case, Offset(41f, 2f), Size(18f, 8f), CornerRadius(3f))
            }
            rotate(42f, pivot = center) {
                drawRoundRect(case, Offset(46f, 10f), Size(8f, 7f), CornerRadius(2f))
            }
            drawCircle(case, radius = if (mini) 44f else 41f, center = center)
            drawCircle(dial, radius = if (mini) 34f else 35f, center = center)
            if (mini) {
                // A glance-sized icon: four bold ticks and a thick hand, no subdial.
                for (tick in 0 until 4) {
                    rotate(tick * 90f, pivot = center) {
                        drawLine(
                            ink,
                            Offset(50f, 31f),
                            Offset(50f, 38f),
                            strokeWidth = 7f,
                            cap = StrokeCap.Round,
                            alpha = 0.6f,
                        )
                    }
                }
                rotate(secondAngle, pivot = center) {
                    drawLine(
                        case,
                        Offset(50f, 66f),
                        Offset(50f, 34f),
                        strokeWidth = 9f,
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(case, radius = 5f, center = center)
                return@scale
            }
            for (tick in 0 until 60) {
                val major = tick % 5 == 0
                rotate(tick * 6f, pivot = center) {
                    drawLine(
                        color = if (major) ink else muted,
                        start = Offset(50f, 28.5f),
                        end = Offset(50f, if (major) 34f else 31.5f),
                        strokeWidth = if (major) 1.6f else 0.8f,
                        cap = StrokeCap.Round,
                        alpha = if (major) 0.8f else 0.55f,
                    )
                }
            }
            val subCenter = Offset(50f, 76f)
            drawCircle(wash, radius = 8.5f, center = subCenter)
            rotate(minuteAngle, pivot = subCenter) {
                drawLine(
                    ink,
                    subCenter,
                    Offset(50f, 69.5f),
                    strokeWidth = 1.6f,
                    cap = StrokeCap.Round,
                )
            }
            rotate(secondAngle, pivot = center) {
                drawLine(
                    case,
                    Offset(50f, 70f),
                    Offset(50f, 31f),
                    strokeWidth = 1.8f,
                    cap = StrokeCap.Round,
                )
                drawCircle(case, radius = 3.4f, center = Offset(50f, 71.5f))
            }
            drawCircle(dial, radius = 3.1f, center = center)
            drawCircle(case, radius = 2.6f, center = center)
        }
    }
}

/**
 * The timer's place in the project top bar: a small stopwatch (its hand sweeps while running) and,
 * once the timer has time on it, a short "12:34" / "1:02:03". Tapping it opens the timer dialog.
 * [attention] (a due reminder) tints it so it is not missed.
 */
@Composable
internal fun ProjectTimerChip(
    totalSeconds: Long,
    running: Boolean,
    attention: Boolean,
    onClick: () -> Unit,
) {
    val label = shortTimerLabel(totalSeconds, running)
    val title = stringResource(R.string.project_timer_title)
    val description = if (label.isEmpty()) title else "$title: $label"
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color =
            when {
                attention -> MaterialTheme.colorScheme.tertiaryContainer
                running -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Transparent
            },
        contentColor =
            if (running) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Stopwatch(totalSeconds = totalSeconds, running = running, size = 22.dp, mini = true)
            if (label.isNotEmpty())
                Text(
                    label,
                    style =
                        MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {},
                )
        }
    }
}

/** "12:34" under an hour, "1:02:03" from then on; empty for an untouched, stopped timer. */
internal fun shortTimerLabel(totalSeconds: Long, running: Boolean): String {
    if (totalSeconds <= 0 && !running) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
