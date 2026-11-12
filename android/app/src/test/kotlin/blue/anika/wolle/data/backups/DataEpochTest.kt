package blue.anika.wolle.data.backups

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `web/src/lib/data-epoch.test.ts`. */
class DataEpochTest {
    private class MemoryStore(override var dataEpoch: String? = null) : EpochStore

    @Test
    fun `compares the stored and the current epoch`() {
        assertEquals(EpochDecision.FIRST, decideEpoch(null, "a"))
        assertEquals(EpochDecision.UNCHANGED, decideEpoch("a", "a"))
        assertEquals(EpochDecision.CHANGED, decideEpoch("a", "b"))
        assertEquals(EpochDecision.UNCHANGED, decideEpoch("a", null))
        assertEquals(EpochDecision.UNCHANGED, decideEpoch("a", ""))
        assertEquals(EpochDecision.UNCHANGED, decideEpoch(null, null))
    }

    @Test
    fun `remembers the first epoch without discarding anything`() = runTest {
        val store = MemoryStore()
        var discarded = 0
        val tracker = DataEpochTracker(store) { discarded += 1 }
        assertEquals(EpochDecision.FIRST, tracker.observe("one"))
        assertEquals("one", store.dataEpoch)
        assertEquals(0, discarded)
    }

    @Test
    fun `discards local data after a restore and then remembers the new epoch`() = runTest {
        val store = MemoryStore("one")
        var epochSeenByDiscard: String? = "unset"
        val tracker = DataEpochTracker(store) { epochSeenByDiscard = store.dataEpoch }
        assertEquals(EpochDecision.CHANGED, tracker.observe("two"))
        // The old epoch is still stored while discarding: a crash in between repeats the wipe.
        assertEquals("one", epochSeenByDiscard)
        assertEquals("two", store.dataEpoch)
        assertEquals(EpochDecision.UNCHANGED, tracker.observe("two"))
    }

    @Test
    fun `a failing discard keeps the old epoch so the next check retries`() = runTest {
        val store = MemoryStore("one")
        val tracker = DataEpochTracker(store) { error("disk full") }
        try {
            tracker.observe("two")
        } catch (_: IllegalStateException) {}
        assertEquals("one", store.dataEpoch)
    }

    @Test
    fun `does nothing without an epoch`() = runTest {
        val store = MemoryStore("one")
        var discarded = 0
        val tracker = DataEpochTracker(store) { discarded += 1 }
        assertEquals(EpochDecision.UNCHANGED, tracker.observe(null))
        assertEquals("one", store.dataEpoch)
        assertEquals(0, discarded)
    }
}
