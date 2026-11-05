package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.PrivilegedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuStatusTest {

    private fun ui(
        state: PrivilegedState,
        developer: Boolean = false,
        supported: Boolean = true,
    ) = shizukuStatusUi(developer, supported, state)

    private val hints =
        mapOf(
            PrivilegedState.NOT_RUNNING to ShizukuHint.NOT_RUNNING,
            PrivilegedState.PERMISSION_NEEDED to ShizukuHint.PERMISSION_NEEDED,
            PrivilegedState.PERMISSION_DENIED to ShizukuHint.PERMISSION_DENIED,
            PrivilegedState.READY to ShizukuHint.READY,
        )

    @Test
    fun `every state but unavailable is visible with its hint, in developer mode or not`() {
        hints.forEach { (state, hint) ->
            listOf(true, false).forEach { developer ->
                val ui = ui(state, developer = developer)
                assertTrue("$state developer=$developer", ui.visible)
                assertEquals(hint, ui.hint)
            }
        }
    }

    @Test
    fun `a missing Shizuku shows up in developer mode only`() {
        val on = ui(PrivilegedState.UNAVAILABLE, developer = true)
        assertTrue(on.visible)
        assertEquals(ShizukuHint.NOT_INSTALLED, on.hint)

        val off = ui(PrivilegedState.UNAVAILABLE, developer = false)
        assertFalse(off.visible)
        assertNull(off.hint)
    }

    @Test
    fun `a build without Shizuku hides the row in every state`() {
        PrivilegedState.entries.forEach { state ->
            listOf(true, false).forEach { developer ->
                val ui = ui(state, developer = developer, supported = false)
                assertFalse(ui.visible)
                assertNull(ui.hint)
            }
        }
    }
}
