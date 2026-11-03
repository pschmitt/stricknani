package blue.anika.wolle.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDismissTest {
    private val width = 400f

    @Test
    fun dismissesPastTheThresholdInEitherDirection() {
        assertEquals(SwipeOutcome.Dismiss(1), swipeOutcome(160f, width, 0f, 1f))
        assertEquals(SwipeOutcome.Dismiss(-1), swipeOutcome(-160f, width, 0f, 1f))
    }

    @Test
    fun springsBackShortOfTheThresholdWhenSlow() {
        assertEquals(SwipeOutcome.Restore, swipeOutcome(100f, width, 50f, 1f))
    }

    @Test
    fun dismissesOnAFastFlingInTheDragDirection() {
        assertEquals(SwipeOutcome.Dismiss(1), swipeOutcome(60f, width, 900f, 1f))
        assertEquals(SwipeOutcome.Dismiss(-1), swipeOutcome(-60f, width, -900f, 1f))
    }

    @Test
    fun ignoresAFlingAgainstTheDragOrATwitch() {
        assertEquals(SwipeOutcome.Restore, swipeOutcome(60f, width, -900f, 1f))
        assertEquals(SwipeOutcome.Restore, swipeOutcome(8f, width, 3000f, 1f))
    }

    @Test
    fun flingThresholdScalesWithDensity() {
        // 500 px/s is a fling at 1x but not at 2x (needs 900 px/s).
        assertEquals(SwipeOutcome.Dismiss(1), swipeOutcome(60f, width, 500f, 1f))
        assertEquals(SwipeOutcome.Restore, swipeOutcome(60f, width, 500f, 2f))
    }

    @Test
    fun neverDismissesWithoutWidthOrMovement() {
        assertEquals(SwipeOutcome.Restore, swipeOutcome(0f, width, 3000f, 1f))
        assertEquals(SwipeOutcome.Restore, swipeOutcome(50f, 0f, 3000f, 1f))
    }

    @Test
    fun dragTiltsWithDirectionAndFadesWithDistance() {
        assertEquals(0f, dragRotation(0f, width), 0f)
        assertTrue(dragRotation(200f, width) > 0f)
        assertTrue(dragRotation(-200f, width) < 0f)
        assertEquals(1f, dragAlpha(0f, width), 0f)
        assertTrue(dragAlpha(200f, width) < 1f)
        assertTrue(dragAlpha(9999f, width) >= 0.25f)
    }

    @Test
    fun puffMixesHeartsAndYarn() {
        val bits = dismissParticles({ 0.5f })
        assertEquals(9, bits.size)
        assertTrue(bits.any { it.heart })
        assertTrue(bits.any { !it.heart })
    }
}
