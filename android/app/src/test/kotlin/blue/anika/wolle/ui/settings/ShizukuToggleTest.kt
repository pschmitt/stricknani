package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.PrivilegedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuToggleTest {

    private fun ui(
        state: PrivilegedState,
        pref: Boolean = true,
        developer: Boolean = true,
        supported: Boolean = true,
    ) = shizukuToggleUi(developer, supported, pref, state)

    @Test
    fun `granted permission leaves the switch usable and on`() {
        val ui = ui(PrivilegedState.READY)
        assertTrue(ui.visible && ui.enabled && ui.checked)
        assertEquals(ShizukuHint.READY, ui.hint)
    }

    @Test
    fun `a permission that can still be asked for keeps the switch usable`() {
        val ui = ui(PrivilegedState.PERMISSION_NEEDED, pref = false)
        assertTrue(ui.visible && ui.enabled)
        assertFalse(ui.checked)
        assertEquals(ShizukuHint.PERMISSION_NEEDED, ui.hint)
    }

    @Test
    fun `a denied permission turns the switch off and disables it`() {
        val ui = ui(PrivilegedState.PERMISSION_DENIED)
        assertTrue(ui.visible)
        assertFalse(ui.enabled)
        assertFalse(ui.checked)
        assertEquals(ShizukuHint.PERMISSION_DENIED, ui.hint)
    }

    @Test
    fun `a service that is not running disables the switch`() {
        val ui = ui(PrivilegedState.NOT_RUNNING)
        assertTrue(ui.visible)
        assertFalse(ui.enabled)
        assertFalse(ui.checked)
        assertEquals(ShizukuHint.NOT_RUNNING, ui.hint)
    }

    @Test
    fun `a missing Shizuku disables the switch with a hint`() {
        val ui = ui(PrivilegedState.UNAVAILABLE)
        assertTrue(ui.visible)
        assertFalse(ui.enabled)
        assertEquals(ShizukuHint.NOT_INSTALLED, ui.hint)
    }

    @Test
    fun `without developer mode the row is hidden in every state`() {
        PrivilegedState.entries.forEach {
            val ui = ui(it, developer = false)
            assertFalse(ui.visible)
            assertFalse(ui.enabled)
            assertNull(ui.hint)
        }
    }

    @Test
    fun `a build without Shizuku hides the row`() {
        assertFalse(ui(PrivilegedState.READY, supported = false).visible)
    }
}
