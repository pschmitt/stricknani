package blue.anika.wolle.ui.common

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One particle of the favourite burst, in dp from the heart's centre. */
data class BurstParticle(val dx: Float, val dy: Float, val size: Float, val delayFraction: Float)

/**
 * Evenly spaced particles, starting straight up. Every second one travels further and is smaller,
 * which reads as confetti and not as a ring. Mirrors `favorite-burst.ts` on the web.
 */
fun burstParticles(
    count: Int = 8,
    nearRadius: Float = 24f,
    farRadius: Float = 34f,
): List<BurstParticle> =
    List(count) { index ->
        val angle = -PI / 2 + index * 2 * PI / count
        val far = index % 2 == 1
        val radius = if (far) farRadius else nearRadius
        BurstParticle(
            dx = (cos(angle) * radius).toFloat(),
            dy = (sin(angle) * radius).toFloat(),
            size = if (far) 6f else 8f,
            delayFraction = if (far) 0.08f else 0f,
        )
    }
