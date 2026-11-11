package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.ui.common.OwnerChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectsListViewModelTest {
    private val projects =
        listOf(
            project(1, category = "Socks", tags = listOf("quick", "gift")),
            project(2, category = "Sweaters", tags = listOf("gift")),
            project(3, category = "Socks", tags = listOf("lace")),
        )

    @Test
    fun `tags are unique and sorted independently of project order`() {
        val tags =
            projectTags(projects.reversed()) { entity ->
                when (entity.id) {
                    1 -> listOf("gift", "quick")
                    2 -> listOf("gift")
                    else -> listOf("lace")
                }
            }

        assertEquals(listOf("gift", "lace", "quick"), tags)
    }

    @Test
    fun `category and tag filters can be combined`() {
        val filtered =
            filterProjects(
                entities = projects,
                query = "",
                category = "Socks",
                tag = "gift",
                decodeTags = { entity ->
                    when (entity.id) {
                        1 -> listOf("quick", "gift")
                        2 -> listOf("gift")
                        else -> listOf("lace")
                    }
                },
            )

        assertEquals(listOf(1), filtered.map { it.id })
    }

    @Test
    fun `tag filtering ignores case to match server normalization`() {
        val filtered =
            filterProjects(
                entities = projects,
                query = "",
                category = null,
                tag = "Gift",
                decodeTags = { entity -> if (entity.id == 1) listOf("gift") else emptyList() },
            )

        assertEquals(listOf(1), filtered.map { it.id })
    }

    @Test
    fun `status filter keeps only that status and null shows every project`() {
        val all =
            listOf(
                project(1, "Socks", emptyList()),
                project(2, "Socks", emptyList(), status = "done"),
                project(3, "Hats", emptyList(), status = "done"),
            )
        fun ids(status: String?, favoritesOnly: Boolean = false) =
            filterProjects(
                    entities = all,
                    query = "",
                    category = null,
                    tag = null,
                    decodeTags = { emptyList() },
                    favoritesOnly = favoritesOnly,
                    status = status,
                )
                .map { it.id }

        assertEquals(listOf(1, 2, 3), ids(null))
        assertEquals(listOf(1), ids("in_progress"))
        assertEquals(listOf(2, 3), ids("done"))
        assertEquals(emptyList<Int>(), ids("done", favoritesOnly = true))
    }

    @Test
    fun `owner filter combines with the status filter`() {
        val all =
            listOf(
                project(1, "Socks", emptyList()),
                project(2, "Socks", emptyList(), status = "done", ownerId = 7),
                project(3, "Hats", emptyList(), ownerId = 7),
                project(4, "Hats", emptyList(), status = "done", ownerId = 8),
            )
        fun ids(owner: OwnerChoice?, status: String? = null) =
            filterProjects(
                    entities = all,
                    query = "",
                    category = null,
                    tag = null,
                    decodeTags = { emptyList() },
                    status = status,
                    owner = owner,
                    currentUserId = 5,
                )
                .map { it.id }

        assertEquals(listOf(1), ids(OwnerChoice.Mine))
        assertEquals(listOf(2, 3, 4), ids(OwnerChoice.Shared))
        assertEquals(listOf(2, 3), ids(OwnerChoice.User(7)))
        assertEquals(listOf(2), ids(OwnerChoice.User(7), status = "done"))
        assertEquals(listOf(2, 4), ids(OwnerChoice.Shared, status = "done"))
    }

    @Test
    fun `the Trash filter keeps only trashed projects and costs nothing when off`() {
        val all = listOf(project(1, "Socks", emptyList()), project(2, "Hats", emptyList()))
        val trashed = setOf(2)
        fun ids(trashedOnly: Boolean) =
            filterProjects(
                    entities = all,
                    query = "",
                    category = null,
                    tag = null,
                    decodeTags = { emptyList() },
                    trashedOnly = trashedOnly,
                    isTrashed = { it.id in trashed },
                )
                .map { it.id }

        assertEquals(listOf(1, 2), ids(trashedOnly = false))
        assertEquals(listOf(2), ids(trashedOnly = true))
    }

    private companion object {
        fun project(
            id: Int,
            category: String,
            tags: List<String>,
            status: String = "in_progress",
            ownerId: Long? = null,
        ) =
            ProjectEntity(
                id = id,
                name = "Project $id",
                category = category,
                tagsJson = tags.joinToString(prefix = "[", postfix = "]"),
                isFavorite = false,
                status = status,
                updatedAt = 0L,
                previewUrl = null,
                detailJson = "{}",
                ownerId = ownerId,
            )
    }
}
