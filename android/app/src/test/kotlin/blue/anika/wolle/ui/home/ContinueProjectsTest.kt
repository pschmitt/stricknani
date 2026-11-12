package blue.anika.wolle.ui.home

import blue.anika.wolle.data.db.entity.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueProjectsTest {
    private val me = 5L

    private fun project(
        id: Int,
        status: String = "in_progress",
        ownerId: Long? = null,
        updatedAt: Long = 0L,
        accessRole: String? = null,
        accessShareRole: String? = null,
    ) =
        ProjectEntity(
            id = id,
            name = "Project $id",
            category = null,
            tagsJson = "[]",
            isFavorite = false,
            status = status,
            updatedAt = updatedAt,
            previewUrl = null,
            detailJson = "{}",
            ownerId = ownerId,
            accessRole = accessRole,
            accessShareRole = accessShareRole,
        )

    @Test
    fun `keeps owned and explicitly shared projects but not unrelated admin-visible ones`() {
        val all =
            listOf(
                project(1),
                project(2, status = "done"),
                project(3, ownerId = me),
                project(4, ownerId = 7),
                project(5),
                project(6, ownerId = 8, accessRole = "viewer"),
                project(7, ownerId = 9, accessRole = "editor"),
                project(8, ownerId = 10, accessRole = "admin"),
                project(9, ownerId = 11, accessRole = "admin", accessShareRole = "viewer"),
            )

        assertEquals(listOf(1, 3, 5, 6, 7, 9), continueProjects(all, me).map { it.id })
    }

    @Test
    fun `orders by most recently updated first and keeps ties in the given order`() {
        val all =
            listOf(
                project(1, updatedAt = 100),
                project(2, updatedAt = 500),
                project(3, updatedAt = 500),
                project(4, updatedAt = 300),
            )

        assertEquals(listOf(2, 3, 4, 1), continueProjects(all, me).map { it.id })
    }

    @Test
    fun `a finished project edited last does not lead the carousel`() {
        val all = listOf(project(1, status = "done", updatedAt = 900), project(2, updatedAt = 100))

        assertEquals(listOf(2), continueProjects(all, me).map { it.id })
    }

    @Test
    fun `caps the carousel at the limit, keeping the newest`() {
        val all = (1..CONTINUE_LIMIT + 3).map { project(it, updatedAt = it.toLong()) }

        val shown = continueProjects(all, me)

        assertEquals(CONTINUE_LIMIT, shown.size)
        assertEquals(CONTINUE_LIMIT + 3, shown.first().id)
    }

    @Test
    fun `nothing in progress leaves the carousel empty so Home shows the empty card`() {
        assertEquals(emptyList<ProjectEntity>(), continueProjects(listOf(project(1, "done")), me))
        assertEquals(emptyList<ProjectEntity>(), continueProjects(emptyList(), me))
    }
}
