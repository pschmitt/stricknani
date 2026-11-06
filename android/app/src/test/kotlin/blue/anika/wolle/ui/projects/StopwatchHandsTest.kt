package blue.anika.wolle.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchHandsTest {
    @Test
    fun `a one-second tick sweeps forward, also across a minute`() {
        val hands = StopwatchHands()
        hands.update(59)
        hands.update(60)
        assertTrue(hands.tickStep)
        assertEquals(360f, hands.second, 0.001f)
    }

    @Test
    fun `a jump moves the hand once, the short way round`() {
        val hands = StopwatchHands()
        hands.update(10) // 60 degrees
        hands.update(10 + 3600 + 50) // back from the background: 0 degrees target, 1h later
        assertFalse(hands.tickStep)
        assertEquals(0f, hands.second, 0.001f) // -60, not several turns forward
    }

    @Test
    fun `a refresh one second back turns back once`() {
        val hands = StopwatchHands()
        hands.update(30)
        hands.update(29)
        assertFalse(hands.tickStep)
        assertEquals(174f, hands.second, 0.001f)
    }
}
