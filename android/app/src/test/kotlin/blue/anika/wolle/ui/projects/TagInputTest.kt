package blue.anika.wolle.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class TagInputTest {
    @Test
    fun `a comma ends a tag`() {
        assertEquals(TagSplit(listOf("test"), "test2"), splitTagInput("test, test2"))
        assertEquals(listOf("test", "test2"), splitTagInput("test, test2", commitAll = true).tags)
    }

    @Test
    fun `a space ends a tag too`() {
        assertEquals(
            TagSplit(listOf("winter", "gift"), "socks"),
            splitTagInput("winter gift, socks"),
        )
        assertEquals(listOf("a", "b", "c"), splitTagInput("a, b c", commitAll = true).tags)
    }

    @Test
    fun `the last piece stays in the field until a separator follows`() {
        assertEquals(TagSplit(emptyList(), "wo"), splitTagInput("wo"))
        assertEquals(TagSplit(listOf("wool"), ""), splitTagInput("wool "))
        assertEquals(TagSplit(emptyList(), ""), splitTagInput(""))
    }

    @Test
    fun `drops blanks and case-insensitive duplicates`() {
        assertEquals(
            listOf("Socks", "wool"),
            mergeTags(emptyList(), listOf("Socks", "socks", "wool")),
        )
        assertEquals(listOf("Socks"), mergeTags(listOf("Socks"), listOf("SOCKS")))
        assertEquals(emptyList<String>(), splitTagInput(" , ,", commitAll = true).tags)
    }

    @Test
    fun `typing a space turns the text into a chip`() {
        val form = ProjectEditorFormState().withTagText("wool ")
        assertEquals(listOf("wool"), form.tags)
        assertEquals("", form.tagInput)
        assertEquals("wo", form.withTagText("wo").tagInput)
    }

    @Test
    fun `a paste commits every piece`() {
        val form = ProjectEditorFormState(tags = listOf("wool")).withTagText("a, b c, WOOL", true)
        assertEquals(listOf("wool", "a", "b", "c"), form.tags)
        assertEquals("", form.tagInput)
    }

    @Test
    fun `removing a chip keeps the others`() {
        val form = ProjectEditorFormState(tags = listOf("a", "b", "c"))
        assertEquals(listOf("a", "c"), form.withoutTag(1).tags)
        assertEquals(form, form.withoutTag(5))
    }

    @Test
    fun `backspace in the empty field removes the last chip only`() {
        val form = ProjectEditorFormState(tags = listOf("a", "b"))
        assertEquals(listOf("a"), form.withoutLastTagIfInputEmpty().tags)
        val typing = form.copy(tagInput = "x")
        assertEquals(typing, typing.withoutLastTagIfInputEmpty())
        assertEquals(
            ProjectEditorFormState(),
            ProjectEditorFormState().withoutLastTagIfInputEmpty(),
        )
    }

    @Test
    fun `saving takes the text still in the field`() {
        val form = ProjectEditorFormState(tags = listOf("a"), tagInput = "b, A c")
        assertEquals(listOf("a", "b", "c"), form.committedTags())
        assertEquals(listOf("a"), ProjectEditorFormState(tags = listOf("a")).committedTags())
    }
}
