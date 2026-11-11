package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the accidental project delete: a delete is only sent for the project the
 * user was asked about, from a screen that has it loaded, and not for an instant tap.
 */
class DeleteConfirmationTest {
    @Test
    fun `a confirmation for the shown project deletes it`() {
        assertEquals(19, DeleteConfirmation.targetToDelete(19, 19, loaded = true))
    }

    @Test
    fun `nothing asked means nothing is deleted`() {
        assertNull(DeleteConfirmation.targetToDelete(null, 19, loaded = true))
    }

    @Test
    fun `a stale confirmation for another project is ignored`() {
        assertNull(DeleteConfirmation.targetToDelete(7, 19, loaded = true))
    }

    @Test
    fun `an unloaded or unknown screen never deletes`() {
        assertNull(DeleteConfirmation.targetToDelete(19, 19, loaded = false))
        assertNull(DeleteConfirmation.targetToDelete(19, null, loaded = true))
    }

    @Test
    fun `a tap right after the dialog appeared does not confirm`() {
        assertFalse(DeleteConfirmation.isArmed(shownAtMillis = 1_000, nowMillis = 1_050))
        assertTrue(DeleteConfirmation.isArmed(shownAtMillis = 1_000, nowMillis = 1_000 + 700))
    }

    @Test
    fun `only a matching confirmation yields a Confirmed delete`() {
        assertEquals(19, DeleteConfirmation.confirm(19, 19, loaded = true)?.id)
        assertNull(DeleteConfirmation.confirm(null, 19, loaded = true))
        assertNull(DeleteConfirmation.confirm(7, 19, loaded = true))
        assertNull(DeleteConfirmation.confirm(19, 19, loaded = false))
    }
}
