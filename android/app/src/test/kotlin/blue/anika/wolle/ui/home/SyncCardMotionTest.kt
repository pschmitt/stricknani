package blue.anika.wolle.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCardMotionTest {
    @Test
    fun moodFollowsTheSameOrderAsTheBadge() {
        assertEquals(SyncBadgeMood.Busy, syncBadgeMood(true, 2, true))
        assertEquals(SyncBadgeMood.Failed, syncBadgeMood(false, 2, true))
        assertEquals(SyncBadgeMood.Pending, syncBadgeMood(false, 2, false))
        assertEquals(SyncBadgeMood.Done, syncBadgeMood(false, 0, false))
    }

    @Test
    fun finishingARefreshHops() {
        assertEquals(
            SyncBadgeCue.Hop,
            syncBadgeCue(SyncBadgeMood.Busy, SyncBadgeMood.Done),
        )
    }

    @Test
    fun appearingAlreadyCaughtUpStaysCalm() {
        assertEquals(SyncBadgeCue.None, syncBadgeCue(null, SyncBadgeMood.Done))
        assertEquals(SyncBadgeCue.None, syncBadgeCue(SyncBadgeMood.Pending, SyncBadgeMood.Done))
    }

    @Test
    fun failureShakesOnceNotRepeatedly() {
        assertEquals(SyncBadgeCue.Shake, syncBadgeCue(null, SyncBadgeMood.Failed))
        assertEquals(SyncBadgeCue.Shake, syncBadgeCue(SyncBadgeMood.Busy, SyncBadgeMood.Failed))
        assertEquals(SyncBadgeCue.None, syncBadgeCue(SyncBadgeMood.Failed, SyncBadgeMood.Failed))
    }

    @Test
    fun busyAndPendingHaveNoCue() {
        assertEquals(SyncBadgeCue.None, syncBadgeCue(SyncBadgeMood.Done, SyncBadgeMood.Busy))
        assertEquals(SyncBadgeCue.None, syncBadgeCue(SyncBadgeMood.Busy, SyncBadgeMood.Pending))
    }

    @Test
    fun keyframesEndAtRest() {
        assertTrue(SYNC_SHAKE_ANGLES.last() == 0f)
        assertTrue(SYNC_HOP_WIGGLE_ANGLES.last() == 0f)
    }

    @Test
    fun pullRevealIsLinearThenRubberBands() {
        assertEquals(0f, syncPullReveal(-0.3f), 0f)
        assertEquals(0.4f, syncPullReveal(0.4f), 0f)
        assertEquals(1f, syncPullReveal(1f), 0f)
        assertEquals(1.05f, syncPullReveal(1.2f), 0.0001f)
        assertEquals(1.2f, syncPullReveal(10f), 0.0001f)
    }

    @Test
    fun nudgeIsSmallAndClamped() {
        assertEquals(0f, syncPullNudge(0f), 0f)
        assertEquals(0.05f, syncPullNudge(3f), 0f)
    }
}
