package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedControlTest {
    @Test
    fun thumbRestsOnTheSelectedOption() {
        assertEquals(0, segmentThumbIndex(3, 0))
        assertEquals(2, segmentThumbIndex(3, 2))
    }

    @Test
    fun thumbStaysInRangeWhenNothingMatches() {
        assertEquals(0, segmentThumbIndex(3, -1))
        assertEquals(2, segmentThumbIndex(3, 7))
        assertEquals(0, segmentThumbIndex(0, 0))
    }

    @Test
    fun noThumbWithoutASelectedOption() {
        assertTrue(segmentHasThumb(2, 1))
        assertFalse(segmentHasThumb(2, -1))
        assertFalse(segmentHasThumb(2, 2))
        assertFalse(segmentHasThumb(0, 0))
    }
}
