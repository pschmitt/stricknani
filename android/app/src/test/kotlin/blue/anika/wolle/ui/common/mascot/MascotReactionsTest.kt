package blue.anika.wolle.ui.common.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotReactionsTest {
    @Test
    fun firstReactionIsSquish() {
        assertEquals(MascotReaction.Squish, nextReaction(null) { 0.9f })
    }

    @Test
    fun mostTapsFollowTheSequenceAndWrapAround() {
        assertEquals(MascotReaction.WinkSwap, nextReaction(MascotReaction.Squish) { 0.1f })
        assertEquals(MascotReaction.Squish, nextReaction(MascotReaction.Giggle) { 0.1f })
    }

    @Test
    fun theRestPickAnotherReactionAtRandom() {
        val picked =
            (0..9)
                .map { i ->
                    val values = floatArrayOf(0.9f, i / 10f)
                    var n = 0
                    nextReaction(MascotReaction.Squish) { values[n++.coerceAtMost(1)] }
                }
                .toSet()
        assertFalse(MascotReaction.Squish in picked)
        assertFalse(MascotReaction.WinkSwap in picked)
        assertTrue(picked.size > 1)
    }

    @Test
    fun queueHoldsAtMostOnePendingReaction() {
        val queue = ReactionQueue { 0.1f }
        assertEquals(MascotReaction.Squish, queue.request())
        assertNull(queue.request())
        assertEquals(MascotReaction.WinkSwap, queue.pending)
        assertNull(queue.request())
        assertNull(queue.request())
        assertEquals(MascotReaction.WinkSwap, queue.pending)
        assertEquals(MascotReaction.WinkSwap, queue.finish())
        assertNull(queue.pending)
        assertNull(queue.finish())
        assertNull(queue.current)
        assertEquals(MascotReaction.Spin, queue.request())
    }

    @Test
    fun streakCountsQuickTapsAndResetsOnAGap() {
        val streak = TapStreak()
        assertEquals(1, streak.tap(0))
        assertEquals(2, streak.tap(450))
        assertEquals(3, streak.tap(900))
        assertEquals(1, streak.tap(1351))
    }

    @Test
    fun sevenQuickTapsAreTheEasterEggOnce() {
        val streak = TapStreak()
        val counts = (0 until 8).map { streak.tap(it * 300L) }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 1), counts)
        assertTrue(streak.isEasterEgg(7))
        assertFalse(streak.isEasterEgg(6))
    }

    @Test
    fun slowTapsNeverTriggerTheEasterEgg() {
        val streak = TapStreak()
        assertTrue((0 until 20).all { !streak.isEasterEgg(streak.tap(it * 1000L)) })
    }

    @Test
    fun heartsComeInThreeToFive() {
        assertEquals(3, heartParticles { 0f }.size)
        assertEquals(5, heartParticles { 0.999f }.size)
        assertTrue(heartParticles { 0.5f }.all { it.size > 0f && it.delayFraction < 1f })
    }

    @Test
    fun winkSwapClosesBothEyesThenSwapsTheWink() {
        assertEquals(Eyes(), eyePose(MascotReaction.WinkSwap, 0.05f, false))
        assertEquals(
            Eyes(EyeShape.Arc, EyeShape.Arc),
            eyePose(MascotReaction.WinkSwap, 0.3f, false),
        )
        assertEquals(
            Eyes(EyeShape.Arc, EyeShape.Dot),
            eyePose(MascotReaction.WinkSwap, 0.6f, false),
        )
        assertEquals(Eyes(), eyePose(MascotReaction.WinkSwap, 0.95f, false))
    }

    @Test
    fun dizzyOverridesTheEyes() {
        assertEquals(Eyes(EyeShape.Cross, EyeShape.Cross), eyePose(null, 0f, true))
        assertNotEquals(Eyes(), eyePose(MascotReaction.WinkSwap, 0.3f, true))
    }

    @Test
    fun giggleAmountPeaksInTheMiddleOfAGiggle() {
        assertEquals(0f, giggleAmount(MascotReaction.Giggle, 0f), 0.001f)
        assertEquals(1f, giggleAmount(MascotReaction.Giggle, 0.5f), 0.001f)
        assertEquals(0f, giggleAmount(MascotReaction.Squish, 0.5f), 0f)
    }
}
