package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.db.entity.ProjectEntity
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

    private companion object {
        fun project(id: Int, category: String, tags: List<String>) =
            ProjectEntity(
                id = id,
                name = "Project $id",
                category = category,
                tagsJson = tags.joinToString(prefix = "[", postfix = "]"),
                isFavorite = false,
                updatedAt = 0L,
                previewUrl = null,
                detailJson = "{}",
            )
    }
}
