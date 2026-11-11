package blue.anika.wolle.ui.trash

import blue.anika.wolle.data.repository.TrashKind
import blue.anika.wolle.data.repository.TrashRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveRemovalsTest {
    @Test
    fun `steps left out and images removed are trashed, steps first`() {
        val trashed =
            trashedByProjectSave(
                initialStepIds = listOf(10, 11, 12),
                currentStepIds = listOf(10, null, 12),
                removedImageIds = listOf(5, 5, 6),
            )

        assertEquals(
            listOf(
                TrashRef(TrashKind.Step, 11),
                TrashRef(TrashKind.Image, 5),
                TrashRef(TrashKind.Image, 6),
            ),
            trashed,
        )
    }

    @Test
    fun `a save that removed nothing trashes nothing`() {
        assertTrue(trashedByProjectSave(listOf(1, 2), listOf(2, 1, null), emptyList()).isEmpty())
        assertTrue(trashedByProjectSave(emptyList(), emptyList(), emptyList()).isEmpty())
        assertTrue(trashedByYarnSave(emptyList()).isEmpty())
    }

    @Test
    fun `new steps without an id are never trashed`() {
        assertTrue(trashedByProjectSave(listOf(null, 3), listOf(3), emptyList()).isEmpty())
    }

    @Test
    fun `yarn photos are trashed once each`() {
        assertEquals(
            listOf(TrashRef(TrashKind.Photo, 4), TrashRef(TrashKind.Photo, 9)),
            trashedByYarnSave(listOf(4, 9, 4)),
        )
    }
}
