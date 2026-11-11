package blue.anika.wolle.ui.home

import blue.anika.wolle.data.db.entity.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class OngoingProjectsTest {
    private val me = 5L

    private fun project(id: Int, status: String = "in_progress", ownerId: Long? = null) =
        ProjectEntity(
            id = id,
            name = "Project $id",
            category = null,
            tagsJson = "[]",
            isFavorite = false,
            status = status,
            updatedAt = 0L,
            previewUrl = null,
            detailJson = "{}",
            ownerId = ownerId,
        )

    @Test
    fun `keeps the user's own projects in progress in the given order`() {
        val all =
            listOf(
                project(1),
                project(2, status = "done"),
                project(3, ownerId = me),
                project(4, ownerId = 7),
                project(5),
            )

        assertEquals(listOf(1, 3, 5), ongoingOwnProjects(all, me).map { it.id })
    }

    @Test
    fun `caps the shelf at six like the website`() {
        val all = (1..9).map { project(it) }

        assertEquals((1..6).toList(), ongoingOwnProjects(all, me).map { it.id })
    }

    @Test
    fun `nothing in progress leaves the shelf empty so Home hides it`() {
        assertEquals(emptyList<ProjectEntity>(), ongoingOwnProjects(listOf(project(1, "done")), me))
    }
}
