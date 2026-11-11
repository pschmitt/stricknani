package blue.anika.wolle.ui.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedRemovalsTest {
    private val initial = listOf(1, 2, 3, 4)
    private val id = { photo: Int -> photo }

    @Test
    fun `staged removals are the removed photos in their original order`() {
        assertEquals(listOf(2, 4), stagedRemovals(initial, listOf(4, 2), id))
        assertEquals(emptyList<Int>(), stagedRemovals(initial, emptyList(), id))
        assertEquals(emptyList<Int>(), stagedRemovals(initial, listOf(9), id))
    }

    @Test
    fun `restoring puts the photo back right after the one that preceded it`() {
        assertEquals(listOf(1, 2, 3, 4), restoredInOrder(listOf(1, 3, 4), initial, 2, id))
        assertEquals(listOf(1, 2, 3, 4), restoredInOrder(listOf(1, 2, 3), initial, 4, id))
    }

    @Test
    fun `restoring the first photo puts it first`() {
        assertEquals(listOf(1, 2, 3), restoredInOrder(listOf(2, 3), initial, 1, id))
    }

    @Test
    fun `restoring follows a reorder done in the meantime`() {
        // 3 was dragged to the front, then 2 was removed: 2 returns after its predecessor 1.
        assertEquals(listOf(3, 1, 2, 4), restoredInOrder(listOf(3, 1, 4), initial, 2, id))
    }

    @Test
    fun `restoring when the predecessors are removed too falls back to the nearest kept one`() {
        assertEquals(listOf(1, 3, 4), restoredInOrder(listOf(1, 4), initial, 3, id))
        assertEquals(
            listOf(1, 2, 3, 4),
            restoredInOrder(listOf(1, 4), initial, 3, id).let {
                restoredInOrder(it, initial, 2, id)
            },
        )
        assertEquals(listOf(3), restoredInOrder(emptyList(), initial, 3, id))
    }

    @Test
    fun `restoring a photo that is present or unknown changes nothing`() {
        assertEquals(listOf(1, 2), restoredInOrder(listOf(1, 2), initial, 2, id))
        assertEquals(listOf(1, 2), restoredInOrder(listOf(1, 2), initial, 9, id))
    }

    @Test
    fun `an existing record with nothing pending shows all changes saved`() {
        val saved = saveBarState(isEditing = true, isDirty = false, isSaving = false)
        assertTrue(saved.allSaved)
        assertFalse(saved.enabled)
    }

    @Test
    fun `a dirty edit or a new record can be saved`() {
        assertTrue(saveBarState(isEditing = true, isDirty = true, isSaving = false).enabled)
        val created = saveBarState(isEditing = false, isDirty = false, isSaving = false)
        assertTrue(created.enabled)
        assertFalse(created.allSaved)
    }

    @Test
    fun `saving disables the button without claiming everything is saved`() {
        val saving = saveBarState(isEditing = true, isDirty = true, isSaving = true)
        assertFalse(saving.enabled)
        assertFalse(saving.allSaved)
        assertFalse(saveBarState(isEditing = true, isDirty = false, isSaving = true).allSaved)
    }

    @Test
    fun `the unsaved line mirrors the web save bar`() {
        assertEquals(null, unsavedStatus(isDirty = false, isSaving = false, stagedCount = 2))
        assertEquals(null, unsavedStatus(isDirty = true, isSaving = true, stagedCount = 2))
        assertEquals(
            UnsavedStatus.Plain,
            unsavedStatus(isDirty = true, isSaving = false, stagedCount = 0),
        )
        assertEquals(
            UnsavedStatus.WithRemovals,
            unsavedStatus(isDirty = true, isSaving = false, stagedCount = 1),
        )
    }
}
