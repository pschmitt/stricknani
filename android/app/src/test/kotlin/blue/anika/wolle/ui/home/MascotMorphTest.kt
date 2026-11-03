package blue.anika.wolle.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class MascotMorphTest {
    @Test
    fun `progress follows the scroll and is clamped`() {
        assertEquals(0f, morphProgress(-30f, 100f, false, 200f), 0f)
        assertEquals(0f, morphProgress(0f, 100f, false, 200f), 0f)
        assertEquals(0.25f, morphProgress(25f, 100f, false, 200f), 0.0001f)
        assertEquals(1f, morphProgress(100f, 100f, false, 200f), 0f)
        assertEquals(1f, morphProgress(900f, 100f, false, 200f), 0f)
    }

    @Test
    fun `an empty range completes at the first scroll`() {
        assertEquals(0f, morphProgress(0f, 0f, false, 0f), 0f)
        assertEquals(1f, morphProgress(1f, 0f, false, 0f), 0f)
    }

    @Test
    fun `without animations the logo appears once the hero is off the top`() {
        assertEquals(0f, morphProgress(150f, 100f, true, 200f), 0f)
        assertEquals(1f, morphProgress(200f, 100f, true, 200f), 0f)
    }

    @Test
    fun `placement rides the list at 0 and sits in the slot at 1`() {
        val start = morphPlacement(0f, 24f, 40f, 48f, 12f, 0.5f)
        assertEquals(MorphPlacement(24f, 40f, 1f), start)
        val end = morphPlacement(1f, 24f, -300f, 48f, 12f, 0.5f)
        assertEquals(MorphPlacement(48f, 12f, 0.5f), end)
        val mid = morphPlacement(0.5f, 24f, 40f, 48f, 12f, 0.5f)
        assertEquals(MorphPlacement(36f, 26f, 0.75f), mid)
    }

    @Test
    fun `the slot opens from zero to full width`() {
        assertEquals(0f, morphSlotWidth(0f, 52f), 0f)
        assertEquals(26f, morphSlotWidth(0.5f, 52f), 0f)
        assertEquals(52f, morphSlotWidth(1f, 52f), 0f)
    }

    @Test
    fun `the bar fills only once the mascot has nearly arrived`() {
        assertEquals(0f, morphBarOpacity(0f), 0f)
        assertEquals(0f, morphBarOpacity(0.6f), 0f)
        assertEquals(0.5f, morphBarOpacity(0.8f), 0.0001f)
        assertEquals(1f, morphBarOpacity(1f), 0f)
    }
}
