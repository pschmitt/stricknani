package blue.anika.wolle.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperModeTapStateTest {
    private val silent = DeveloperModeTapAction.Silent

    @Test
    fun firstTwoTapsAreSilentThenCountsDownFromFour() {
        val state = DeveloperModeTapState()
        val actions = (1..7).map { state.onTap(it * 100L, false) }
        assertEquals(
            listOf(
                silent,
                silent,
                DeveloperModeTapAction.Countdown(4),
                DeveloperModeTapAction.Countdown(3),
                DeveloperModeTapAction.Countdown(2),
                DeveloperModeTapAction.Countdown(1),
                DeveloperModeTapAction.Unlock,
            ),
            actions,
        )
    }

    @Test
    fun pauseOfMoreThanTwoSecondsResetsTheCount() {
        val state = DeveloperModeTapState()
        repeat(4) { state.onTap(it * 100L, false) }
        assertEquals(silent, state.onTap(2_400L, false))
        assertEquals(silent, state.onTap(2_500L, false))
        assertEquals(DeveloperModeTapAction.Countdown(4), state.onTap(2_600L, false))
    }

    @Test
    fun tapsExactlyTwoSecondsApartKeepCounting() {
        val state = DeveloperModeTapState()
        val last = (1..7).map { state.onTap(it * 2_000L, false) }.last()
        assertEquals(DeveloperModeTapAction.Unlock, last)
    }

    @Test
    fun alreadyDeveloperNeverCounts() {
        val state = DeveloperModeTapState()
        repeat(10) {
            assertEquals(DeveloperModeTapAction.AlreadyDeveloper, state.onTap(it * 50L, true))
        }
    }

    @Test
    fun countRestartsAfterUnlockAndAfterReset() {
        val state = DeveloperModeTapState()
        repeat(7) { state.onTap(it * 50L, false) }
        assertEquals(silent, state.onTap(400L, false))
        state.reset()
        assertEquals(silent, state.onTap(450L, false))
        assertEquals(silent, state.onTap(500L, false))
        assertEquals(DeveloperModeTapAction.Countdown(4), state.onTap(550L, false))
    }
}
