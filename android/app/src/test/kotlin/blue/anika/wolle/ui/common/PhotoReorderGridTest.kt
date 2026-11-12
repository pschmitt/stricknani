package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoReorderGridTest {
    @Test
    fun `a phone shows three columns and wider screens more`() {
        assertEquals(3, photoGridColumns(328f))
        assertEquals(3, photoGridColumns(360f))
        assertEquals(5, photoGridColumns(620f))
    }

    @Test
    fun `a dragged thumbnail lands in the slot under its centre across rows`() {
        // Cells 100 apart, 3 columns, 2 rows, 6 reorderable photos.
        assertEquals(0, photoGridSlot(10f, 10f, 100f, 3, 2, 6))
        assertEquals(2, photoGridSlot(250f, 40f, 100f, 3, 2, 6))
        assertEquals(3, photoGridSlot(40f, 130f, 100f, 3, 2, 6))
        assertEquals(4, photoGridSlot(150f, 150f, 100f, 3, 2, 6))
    }

    @Test
    fun `the slot is clamped to the grid and to the reorderable photos`() {
        assertEquals(0, photoGridSlot(-50f, -50f, 100f, 3, 2, 6))
        assertEquals(5, photoGridSlot(900f, 900f, 100f, 3, 2, 6))
        // Photos still waiting to upload follow the reorderable ones and cannot be targeted.
        assertEquals(3, photoGridSlot(250f, 150f, 100f, 3, 2, 4))
    }

    @Test
    fun `no edge scroll in the middle of the visible area`() {
        assertEquals(0f, edgeScrollDelta(300f, 0f, 600f, 72f, 18f), 0f)
        assertEquals(0f, edgeScrollDelta(72f, 0f, 600f, 72f, 18f), 0f)
        assertEquals(0f, edgeScrollDelta(528f, 0f, 600f, 72f, 18f), 0f)
    }

    @Test
    fun `edge scroll speed grows with the depth into the zone`() {
        // Half way into the top zone: 9 px up; at the bottom edge: the top speed down.
        assertEquals(-9f, edgeScrollDelta(36f, 0f, 600f, 72f, 18f), 0f)
        assertEquals(-18f, edgeScrollDelta(0f, 0f, 600f, 72f, 18f), 0f)
        assertEquals(9f, edgeScrollDelta(564f, 0f, 600f, 72f, 18f), 0f)
        assertEquals(18f, edgeScrollDelta(600f, 0f, 600f, 72f, 18f), 0f)
        // Rounded up, so a tiny depth still moves.
        assertEquals(1f, edgeScrollDelta(528.1f, 0f, 600f, 72f, 18f), 0f)
    }

    @Test
    fun `edge scroll speed is capped beyond the container edge`() {
        assertEquals(-18f, edgeScrollDelta(-200f, 0f, 600f, 72f, 18f), 0f)
        assertEquals(18f, edgeScrollDelta(900f, 0f, 600f, 72f, 18f), 0f)
        // The viewport need not start at zero.
        assertEquals(-18f, edgeScrollDelta(100f, 100f, 700f, 72f, 18f), 0f)
    }
}
