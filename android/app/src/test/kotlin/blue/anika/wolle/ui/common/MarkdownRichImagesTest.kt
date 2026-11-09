@file:OptIn(ExperimentalRichTextApi::class)

package blue.anika.wolle.ui.common

import androidx.compose.ui.text.TextRange
import blue.anika.wolle.data.media.resolveMediaUrl
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRichImagesTest {
    private fun roundTrip(markdown: String) = MarkdownRich.roundTrip(markdown, false)

    /** Pictures open in the visual editor and come back byte for byte. */
    @Test
    fun picturesRoundTripExactly() {
        listOf(
                "![a cat](/media/projects/1/cat.jpg)",
                "![a cat](/media/projects/1/cat.jpg){.sn-size-md}",
                "Look ![a cat](/media/projects/1/cat.jpg) here",
                "![x](https://example.com/a/b.png?size=2&v=1)",
                "![Strickmuster [Maße] *Äpfel*](/media/x.jpg)",
                "![Strickmuster \\[Maße\\] \\*Äpfel\\*](/media/x.jpg)",
                "![Schöne Grüße](/media/ü.jpg)",
                "![](/media/empty-alt.jpg)",
                "Before\n\n![one](/media/1.jpg)\n\n![two](/media/2.jpg){.sn-size-xl}\n\nAfter",
                "![one](/media/1.jpg) ![two](/media/2.jpg)",
                "Text **bold ![b](/media/b.jpg) end** after",
                "- item ![i](/media/i.jpg)\n- second",
                "Intro:\n\n1. one\n2. ![two](/media/2.jpg)\n\nOutro",
                "### Heading\n\n![h](/media/h.jpg)",
            )
            .forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun linkedPicturesStayRaw() {
        assertNull(roundTrip("[![a cat](/media/cat.jpg)](https://example.com)"))
    }

    @Test
    fun picturesAreAtomicChipsWithTheirAlt() {
        val state = MarkdownRich.toRich("Hi ![a cat \\[1\\]](/media/c.jpg){.sn-size-md} there")
        val blocks = state.toRichTextDocument().blocks
        assertEquals(1, blocks.size)
        val marks = blocks[0].spans.filterIsInstance<RichTextSpanMark.Token>()
        assertEquals(1, marks.size)
        val chip = "\uD83D\uDDBC a cat [1]"
        assertEquals(3 until 3 + chip.length, marks[0].range)
        assertEquals(EditorImage.TRIGGER, marks[0].trigger)
        assertEquals("![a cat \\[1\\]](/media/c.jpg){.sn-size-md}", marks[0].id)
        assertEquals("Hi $chip there", blocks[0].text)
    }

    @Test
    fun deletingAPictureRemovesItWholeAndTextCanBeTypedAround() {
        val state = MarkdownRich.toRich("ab ![x](/media/x.jpg) cd")
        val range =
            state
                .toRichTextDocument()
                .blocks[0]
                .spans
                .filterIsInstance<RichTextSpanMark.Token>()
                .single()
                .range
        state.removeTextRange(TextRange(range.first, range.last + 1))
        assertEquals("ab  cd", MarkdownRich.toMarkdown(state))
        val typed = MarkdownRich.toRich("![x](/media/x.jpg)")
        typed.addTextAtIndex(0, "before ")
        typed.addTextAtIndex(typed.annotatedString.text.length, " after")
        assertEquals("before ![x](/media/x.jpg) after", MarkdownRich.toMarkdown(typed))
    }

    @Test
    fun aHalfDeletedChipGoesWhole() {
        val state = MarkdownRich.toRich("ab ![Masse](/media/x.jpg) **cd**")
        val range =
            state
                .toRichTextDocument()
                .blocks[0]
                .spans
                .filterIsInstance<RichTextSpanMark.Token>()
                .single()
                .range
        // Backspace removes the last chip character only.
        state.removeTextRange(TextRange(range.last, range.last + 1))
        assertTrue(MarkdownRich.dropDamagedPictures(state))
        assertEquals("ab  **cd**", MarkdownRich.toMarkdown(state))
        assertTrue(!MarkdownRich.dropDamagedPictures(state))
        // An untouched chip stays.
        val intact = MarkdownRich.toRich("ab ![Masse](/media/x.jpg) cd")
        assertTrue(!MarkdownRich.dropDamagedPictures(intact))
    }

    @Test
    fun formattingAroundAPictureKeepsItsRange() {
        val text = "**bold** ![x](/media/x.jpg) and *it* ![yy](/media/y.jpg) end"
        assertEquals(text, roundTrip(text))
    }

    @Test
    fun picturesInCodeAreNotPictures() {
        assertNull(MarkdownRich.extractImages("`![a](b)`").images.firstOrNull())
        assertEquals(0, MarkdownRich.extractImages("\\![a](b)").images.size)
    }

    @Test
    fun aPictureCreatedByTheLibraryIsWrittenWithEscapedAlt() {
        assertEquals(
            "![a \\[b\\] \\*c\\*](/media/x%20y.jpg)",
            EditorImage.markdownOf("/media/x y.jpg", "a [b] *c*"),
        )
        assertEquals(
            "![a](/m.jpg){.sn-size-sm}",
            EditorImage.markdownOf("![a](/m.jpg){.sn-size-sm}", "a"),
        )
    }

    @Test
    fun parsesAltAndDestination() {
        assertEquals("a [1]" to "/media/x.jpg", EditorImage.parse("![a \\[1\\]](/media/x.jpg)"))
        assertEquals("a" to "/media/x y.jpg", EditorImage.parse("![a](</media/x y.jpg>)"))
        assertEquals(
            "" to "https://e.com/x.png",
            EditorImage.parse("![](https://e.com/x.png){.sn-size-md}"),
        )
        assertNull(EditorImage.parse("not a picture"))
    }

    @Test
    fun resolvesPicturesLikeTheReader() {
        val server = "https://wolle.example.com"
        val resolve = { path: String? -> resolveMediaUrl(server, path) }
        assertEquals(
            "https://wolle.example.com/media/projects/1/cat.jpg",
            EditorImage("![c](/media/projects/1/cat.jpg){.sn-size-md}", "c")
                .resolvedSource(resolve),
        )
        assertEquals(
            "https://wolle.example.com/media/a.jpg",
            EditorImage("![c](media/a.jpg)", "c").resolvedSource(resolve),
        )
        assertEquals(
            "https://wolle.example.com/x.png",
            EditorImage("![c](https://wolle.example.com/x.png)", "c").resolvedSource(resolve),
        )
        // Another origin or a script is never loaded: the editor shows the alt text instead.
        assertNull(EditorImage("![c](https://evil.example.org/x.png)", "c").resolvedSource(resolve))
        assertNull(EditorImage("![c](javascript:alert(1))", "c").resolvedSource(resolve))
        assertNull(EditorImage("![c](/media/a.jpg)", "c").resolvedSource { null })
        assertNotNull(EditorImage("![c](/media/a.jpg)", "c").source)
        assertTrue(roundTrip("![c](/media/a.jpg)") != null)
    }

    /**
     * The entry point of MarkdownTextField (`enterVisual` and the outside-change reload):
     * `canEditVisually`, then `MarkdownRich.load` into the editor's state. The picture is a visible
     * token chip in the text (the editor cannot render inline images), not a placeholder glyph.
     */
    @Test
    fun loadingIntoTheEditorStateShowsAVisibleChip() {
        val text = "Vorher ![Masse](/Media/x.jpg) nachher"
        assertTrue(MarkdownRich.canEditVisually(text, false))
        val state = RichTextState()
        MarkdownRich.load(state, text)
        // Loading again (a reload of the same state) behaves the same.
        MarkdownRich.load(state, text)
        val shown = state.annotatedString.text
        assertEquals("Vorher \uD83D\uDDBC Masse nachher", shown)
        assertTrue(shown.none { it.code in 0xE000..0xF8FF || it == '\uFFFC' || it == '\uFFFD' })
        assertEquals(text, MarkdownRich.toMarkdown(state))
    }
}
