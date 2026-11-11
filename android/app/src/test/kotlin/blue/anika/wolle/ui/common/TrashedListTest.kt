package blue.anika.wolle.ui.common

import blue.anika.wolle.data.api.dto.ProjectListItemDto
import blue.anika.wolle.data.api.dto.YarnListItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashedListTest {
    @Test
    fun `an owner choice is sent the way the website writes it`() {
        assertNull(null.toArg())
        assertEquals("me", OwnerChoice.Mine.toArg())
        assertEquals("shared", OwnerChoice.Shared.toArg())
        assertEquals("42", OwnerChoice.User(42).toArg())
        assertEquals(OwnerChoice.User(42), ownerChoiceFromArg(OwnerChoice.User(42).toArg()))
    }

    @Test
    fun `trashed projects are narrowed by search, category, tag and favourites`() {
        val all =
            listOf(
                project(1, "Socks", category = "Socks", tags = listOf("gift")),
                project(2, "Hat", category = "Hats", favorite = true),
                project(3, "Gift socks", category = "Socks", favorite = true),
            )

        fun ids(
            query: String = "",
            category: String? = null,
            tag: String? = null,
            favoritesOnly: Boolean = false,
        ) = filterTrashedProjects(all, query, category, tag, favoritesOnly).map { it.id }

        assertEquals(listOf(1, 2, 3), ids())
        assertEquals(listOf(1, 3), ids(query = "sock"))
        assertEquals(listOf(1, 3), ids(category = "Socks"))
        assertEquals(listOf(1), ids(tag = "GIFT"))
        assertEquals(listOf(2, 3), ids(favoritesOnly = true))
        assertEquals(listOf(3), ids(query = "gift", favoritesOnly = true))
    }

    @Test
    fun `trashed yarns match name, brand and colourway`() {
        val all =
            listOf(
                yarn(1, "Merino", brand = "Drops"),
                yarn(2, "Cotton", colorway = "Merino blue", favorite = true),
            )

        assertEquals(listOf(1, 2), filterTrashedYarns(all, "merino", false).map { it.id })
        assertEquals(listOf(1), filterTrashedYarns(all, "drops", false).map { it.id })
        assertEquals(listOf(2), filterTrashedYarns(all, "", true).map { it.id })
    }

    private fun project(
        id: Int,
        name: String,
        category: String? = null,
        tags: List<String> = emptyList(),
        favorite: Boolean = false,
    ) =
        ProjectListItemDto(
            id = id,
            name = name,
            category = category,
            tags = tags,
            isFavorite = favorite,
            updatedAt = "2026-11-01T10:00:00Z",
        )

    private fun yarn(
        id: Int,
        name: String,
        brand: String? = null,
        colorway: String? = null,
        favorite: Boolean = false,
    ) =
        YarnListItemDto(
            id = id,
            name = name,
            brand = brand,
            colorway = colorway,
            isFavorite = favorite,
            updatedAt = "2026-11-01T10:00:00Z",
        )
}
