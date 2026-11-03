package blue.anika.wolle.ui.common.mascot

import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotPhysicsTest {
    @Test
    fun rubberBandIsLinearNearZeroAndNeverPassesTheLimit() {
        assertEquals(1f, rubberBand(1f, 24f), 0.02f)
        assertEquals(-1f, rubberBand(-1f, 24f), 0.02f)
        assertTrue(rubberBand(10_000f, 24f) <= 24f)
        assertTrue(rubberBand(60f, 24f) > rubberBand(30f, 24f))
    }

    @Test
    fun rubberBandKeepsDirectionInTwoDimensions() {
        val v = rubberBand(Vec2(300f, 400f), 24f)
        assertEquals(0.6f, v.x / v.length, 0.001f)
        assertEquals(0.8f, v.y / v.length, 0.001f)
        assertTrue(v.length <= 24f)
        assertEquals(Vec2.Zero, rubberBand(Vec2.Zero, 24f))
    }

    @Test
    fun springOvershootsThenSettlesAtTarget() {
        var s = Spring1(20f, 0f)
        var crossed = false
        repeat(600) {
            s = springStep(s, 0f, 1f / 60f)
            if (s.pos < -0.5f) crossed = true
        }
        assertTrue("wobbly overshoot", crossed)
        assertTrue(isSettled(s))
    }

    @Test
    fun springIsStableForLongFrames() {
        var s = Spring1(20f, 0f)
        repeat(100) { s = springStep(s, 0f, 0.05f) }
        assertTrue(abs(s.pos) < 1f)
    }

    @Test
    fun slowReleaseIsNoFlick() {
        assertEquals(0f, flickToOmega(300f, 100f), 0f)
    }

    @Test
    fun flickSpinsInTheDirectionOfTheRelease() {
        assertTrue(flickToOmega(2000f, 0f) > 0f)
        assertTrue(flickToOmega(-2000f, 0f) < 0f)
        assertTrue(flickToOmega(0f, 2000f) > 0f)
        assertEquals(MAX_OMEGA, flickToOmega(90_000f, 0f), 0f)
    }

    @Test
    fun frictionDecaysExponentiallyAndSettles() {
        assertEquals(1000f * exp(-1.6f * 0.5f), frictionStep(1000f, 0.5f), 0.01f)
        var omega = 1500f
        var steps = 0
        while (!isSettled(omega) && steps < 10_000) {
            omega = frictionStep(omega, 1f / 60f)
            steps++
        }
        assertTrue(isSettled(omega))
    }

    @Test
    fun addSpinStacksAndClamps() {
        assertEquals(900f, addSpin(500f, 400f), 0f)
        assertEquals(MAX_OMEGA, addSpin(MAX_OMEGA, 400f), 0f)
    }

    @Test
    fun dizzyOnlyWhenSpinningFast() {
        assertTrue(isDizzy(500f))
        assertTrue(isDizzy(-500f))
        assertFalse(isDizzy(200f))
    }

    @Test
    fun lookDirectionPointsAtTheDragAndIsClamped() {
        val v = lookDirection(12f, 0f, 24f)
        assertEquals(0.5f, v.x, 0.001f)
        assertEquals(0f, v.y, 0.001f)
        assertEquals(1f, lookDirection(0f, -500f, 24f).length, 0.001f)
        assertEquals(Vec2.Zero, lookDirection(0f, 0f))
    }

    @Test
    fun wobbleStartsAtZeroAndDecays() {
        assertEquals(0f, wobble(0f, 2f, 3f), 0.0001f)
        assertTrue(abs(wobble(0.95f, 2f, 3f)) < 0.1f)
    }

    @Test
    fun easeOutBackOvershootsAndEndsAtOne() {
        assertEquals(0f, easeOutBack(0f), 0.0001f)
        assertEquals(1f, easeOutBack(1f), 0.0001f)
        assertTrue((1..99).any { easeOutBack(it / 100f) > 1.01f })
    }

    @Test
    fun fidgetDelayStaysWithinEightToFifteenSeconds() {
        assertEquals(8000L, nextFidgetDelay { 0f })
        assertTrue(nextFidgetDelay { 0.9999f } in 14_990L..15_000L)
        assertEquals(11_500L, nextFidgetDelay { 0.5f })
    }

    @Test
    fun pickFidgetCoversEveryFidget() {
        assertEquals(Fidget.Blink, pickFidget { 0f })
        assertEquals(Fidget.MicroBounce, pickFidget { 0.999f })
        assertEquals(Fidget.entries.toSet(), (0..9).map { pickFidget { it / 10f } }.toSet())
    }
}
