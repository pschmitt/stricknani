package blue.anika.wolle.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSaveTest {
    @Test
    fun `background work sits out the battery saver`() {
        assertTrue(shouldSkipBackgroundWork(background = true, powerSaveMode = true))
    }

    @Test
    fun `background work runs without the battery saver`() {
        assertFalse(shouldSkipBackgroundWork(background = true, powerSaveMode = false))
    }

    @Test
    fun `user triggered work always runs`() {
        assertFalse(shouldSkipBackgroundWork(background = false, powerSaveMode = true))
        assertFalse(shouldSkipBackgroundWork(background = false, powerSaveMode = false))
    }
}
