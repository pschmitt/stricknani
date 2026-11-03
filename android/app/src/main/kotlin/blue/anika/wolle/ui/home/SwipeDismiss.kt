package blue.anika.wolle.ui.home

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** Pure decisions behind [DismissibleCard]; mirrors `web/src/lib/swipe-dismiss.ts`. */

/** Share of the card's width a drag must pass to dismiss it. */
const val DISMISS_FRACTION = 0.38f

/** A flick faster than this (dp per second) dismisses even a short drag. */
const val FLING_VELOCITY_DP = 450f

/** ...as long as it travelled at least this far (dp), so a twitch never counts. */
const val FLING_MIN_DISTANCE_DP = 16f

sealed interface SwipeOutcome {
    /** Dismiss towards [direction]: 1 is right, -1 is left. */
    data class Dismiss(val direction: Int) : SwipeOutcome

    data object Restore : SwipeOutcome
}

/** What to do on release: dismiss past the threshold or on a fling, else spring back. */
fun swipeOutcome(dx: Float, width: Float, velocity: Float, density: Float): SwipeOutcome {
    if (width <= 0f || dx == 0f) return SwipeOutcome.Restore
    val direction = if (dx > 0f) 1 else -1
    if (abs(dx) >= width * DISMISS_FRACTION) return SwipeOutcome.Dismiss(direction)
    val sameWay = sign(velocity).toInt() == direction
    if (
        sameWay &&
            abs(velocity) >= FLING_VELOCITY_DP * density &&
            abs(dx) >= FLING_MIN_DISTANCE_DP * density
    ) {
        return SwipeOutcome.Dismiss(direction)
    }
    return SwipeOutcome.Restore
}

private fun dragProgress(dx: Float, width: Float): Float =
    if (width > 0f) min(1f, abs(dx) / width) else 0f

/** The card tilts with the finger, up to 12 degrees. */
fun dragRotation(dx: Float, width: Float): Float = sign(dx) * dragProgress(dx, width) * 12f

/** The card fades with distance, but stays visible until the exit runs. */
fun dragAlpha(dx: Float, width: Float): Float =
    (1f - dragProgress(dx, width) * 0.9f).coerceAtLeast(0.25f)

/** One bit of the puff: a heart or a yarn ball, flung [dx]/[dy] dp from the centre. */
data class DismissParticle(
    val heart: Boolean,
    val dx: Float,
    val dy: Float,
    val size: Float,
    val delayFraction: Float,
    val colorIndex: Int,
)

/** A small puff of hearts and yarn balls. */
fun dismissParticles(rand: () -> Float, count: Int = 9): List<DismissParticle> =
    List(count) { i ->
        val angle = (i.toFloat() / count) * (2f * Math.PI.toFloat()) + (rand() - 0.5f) * 0.6f
        val radius = 36f + rand() * 40f
        DismissParticle(
            heart = i % 3 == 0,
            dx = cos(angle) * radius,
            dy = sin(angle) * radius - 14f,
            size = 4f + rand() * 4f,
            delayFraction = rand() * 0.12f,
            colorIndex = i,
        )
    }
