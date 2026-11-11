package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashFilterTest {
    private val now = java.time.Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()

    @Test
    fun `counts whole days until the trash is emptied, at least one`() {
        assertEquals(
            3,
            trashDaysLeft("2026-10-30T00:00:00Z", "2026-11-04T00:00:00Z", now),
        )
        assertEquals(
            1,
            trashDaysLeft("2026-10-30T00:00:00Z", "2026-11-01T12:30:00Z", now),
        )
        assertEquals(
            1,
            trashDaysLeft("2026-10-30T00:00:00Z", "2026-10-01T00:00:00Z", now),
        )
    }

    @Test
    fun `an item outside the trash or without a readable purge time has no badge`() {
        assertNull(trashDaysLeft(null, "2026-11-04T00:00:00Z", now))
        assertNull(trashDaysLeft("2026-10-30T00:00:00Z", null, now))
        assertNull(trashDaysLeft("2026-10-30T00:00:00Z", "soon", now))
    }
}
