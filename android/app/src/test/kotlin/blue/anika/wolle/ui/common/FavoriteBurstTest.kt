package blue.anika.wolle.ui.common

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteBurstTest {
    @Test
    fun startsStraightUpWithRequestedCount() {
        val particles = burstParticles(8)
        assertEquals(8, particles.size)
        assertEquals(0f, particles[0].dx, 0.001f)
        assertEquals(-24f, particles[0].dy, 0.001f)
    }

    @Test
    fun alternatesNearAndFar() {
        val (near, far) = burstParticles(8)
        assertEquals(24f, hypot(near.dx, near.dy), 0.01f)
        assertEquals(34f, hypot(far.dx, far.dy), 0.01f)
        assertTrue(far.size < near.size)
    }

    @Test
    fun spreadsOverBothSides() {
        val particles = burstParticles(8)
        assertTrue(particles.any { it.dx > 1f })
        assertTrue(particles.any { it.dx < -1f })
    }
}
