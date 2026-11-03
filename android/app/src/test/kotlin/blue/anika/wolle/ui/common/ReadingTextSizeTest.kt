package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTextSizeTest {
    @Test
    fun `steps match the web reader`() {
        assertEquals(listOf(0.9f, 1.0f, 1.15f), ReadingTextSize.factors)
        assertEquals(listOf("90%", "100%", "115%"), (0..2).map(ReadingTextSize::percentLabel))
        assertEquals(1, ReadingTextSize.DEFAULT_STEP)
    }

    @Test
    fun `stepping stops at both ends`() {
        assertEquals(0, ReadingTextSize.stepped(1, -1))
        assertEquals(0, ReadingTextSize.stepped(0, -1))
        assertEquals(2, ReadingTextSize.stepped(1, +1))
        assertEquals(2, ReadingTextSize.stepped(2, +1))
    }

    @Test
    fun `out of range stored values are clamped`() {
        assertEquals(0, ReadingTextSize.clamp(-4))
        assertEquals(2, ReadingTextSize.clamp(9))
        assertEquals(1.15f, ReadingTextSize.factor(9))
        assertEquals("90%", ReadingTextSize.percentLabel(-1))
    }

    @Test
    fun `buttons are disabled at the limits and reset only off default`() {
        assertFalse(ReadingTextSize.canDecrease(0))
        assertTrue(ReadingTextSize.canDecrease(1))
        assertTrue(ReadingTextSize.canIncrease(1))
        assertFalse(ReadingTextSize.canIncrease(2))
        assertTrue(ReadingTextSize.isDefault(1))
        assertFalse(ReadingTextSize.isDefault(0))
        assertFalse(ReadingTextSize.isDefault(2))
    }
}
