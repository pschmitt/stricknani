package blue.anika.wolle.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSuggestionsTest {
    private val known =
        listOf(
            TagCount("winter", 5),
            TagCount("wool", 2),
            TagCount("Gift", 4),
            TagCount("socks", 4),
            TagCount("newborn", 1),
            TagCount("snow", 3),
        )

    private fun names(found: List<TagCount>) = found.map { it.tag }

    @Test
    fun `an empty query lists the most used tags then by name`() {
        assertEquals(
            listOf("winter", "Gift", "socks", "snow", "wool", "newborn"),
            names(suggestTags(known, "", emptyList())),
        )
        assertEquals(2, suggestTags(known, "  ", emptyList(), limit = 2).size)
    }

    @Test
    fun `prefix matches come before substring matches ignoring case`() {
        assertEquals(
            listOf("winter", "wool", "snow", "newborn"),
            names(suggestTags(known, "W", emptyList())),
        )
        assertEquals(listOf("snow"), names(suggestTags(known, "no", emptyList())))
        assertEquals(listOf("Gift"), names(suggestTags(known, "GIF", emptyList())))
    }

    @Test
    fun `chosen and unrelated tags are left out`() {
        assertEquals(
            listOf("wool", "snow", "newborn"),
            names(suggestTags(known, "w", listOf("WINTER"))),
        )
        assertTrue(suggestTags(known, "zzz", emptyList()).isEmpty())
    }

    @Test
    fun `the list is capped`() {
        val many = (0 until 20).map { TagCount("tag$it", it) }
        val found = suggestTags(many, "tag", emptyList())
        assertEquals(MAX_TAG_SUGGESTIONS, found.size)
        assertEquals("tag19", found.first().tag)
    }

    @Test
    fun `counts a tag once per project and merges spellings by case`() {
        val counts =
            tagCounts(
                listOf(
                    listOf("winter", "socks", "Gift"),
                    listOf("winter", "gift", "gift"),
                    listOf("winter", "baby blanket", " ", "gift"),
                    listOf("socks"),
                )
            )
        assertEquals(
            listOf(
                TagCount("gift", 3),
                TagCount("winter", 3),
                TagCount("socks", 2),
                TagCount("baby blanket", 1),
            ),
            counts,
        )
    }

    @Test
    fun `picking a tag adds the chip and clears the typed text`() {
        val form = ProjectEditorFormState(tags = listOf("a"), tagInput = "wi")
        assertEquals(listOf("a", "winter"), form.withPickedTag("winter").tags)
        assertEquals("", form.withPickedTag("winter").tagInput)
        assertEquals(listOf("a"), form.withPickedTag("A").tags)
    }
}
