@file:OptIn(ExperimentalRichTextApi::class)

package blue.anika.wolle.ui.common

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.TextUnit
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEditorThumbnailsTest {
    private val picture = "![Test](/media/projects/1/t.jpg){.sn-size-md}"

    private fun tokens(state: RichTextState) =
        state.toRichTextDocument().blocks.flatMap { block ->
            block.spans.filterIsInstance<RichTextSpanMark.Token>()
        }

    @Test
    fun aPictureAloneInItsParagraphIsAThumbnail() {
        val state = MarkdownRich.toRich("Intro\n\n$picture\n\nOutro")
        val blocks = state.toRichTextDocument().blocks
        assertEquals(5, blocks.size)
        val token = tokens(state).single()
        assertEquals(EditorImage.THUMB_TRIGGER, token.trigger)
        assertEquals(EditorImage.THUMB_LABEL, token.label)
        assertEquals(picture, token.id)
        assertEquals(EditorImage.THUMB_LABEL, blocks[2].text)
        assertEquals(MarkdownRich.DEFAULT_THUMB_LINE_HEIGHT, blocks[2].lineHeight)
        assertEquals(TextUnit.Unspecified, blocks[0].lineHeight)
        assertEquals(TextUnit.Unspecified, blocks[4].lineHeight)
        assertEquals("Intro\n\n$picture\n\nOutro", MarkdownRich.toMarkdown(state))
    }

    @Test
    fun aPictureInTheMiddleOfALineStaysAChip() {
        val state = MarkdownRich.toRich("Hi $picture there")
        val token = tokens(state).single()
        assertEquals(EditorImage.TRIGGER, token.trigger)
        assertEquals("🖼 Test", token.label)
        assertEquals(TextUnit.Unspecified, state.toRichTextDocument().blocks[0].lineHeight)
        assertFalse(MarkdownRich.relayoutPictures(state))
        assertEquals("Hi $picture there", MarkdownRich.toMarkdown(state))
    }

    @Test
    fun thumbnailsAreFoundByTheirCharacter() {
        val state =
            MarkdownRich.toRich("a\n\n$picture\n\n![Zwei](/media/2.jpg)\n\nb ![chip](/media/3.jpg)")
        val found = MarkdownRich.thumbnails(state)
        val text = state.annotatedString.text
        assertEquals(2, found.size)
        found.forEach { assertEquals('￼', text[it.offset]) }
        assertEquals("Test", found[0].image.alt)
        assertEquals(MarkdownImageSize.MD, found[0].image.size)
        assertEquals("Zwei", found[1].image.alt)
        assertEquals(null, found[1].image.size)
    }

    @Test
    fun typingNextToAThumbnailTurnsItIntoAChipAndBack() {
        val state = MarkdownRich.toRich(picture)
        state.addTextAtIndex(0, "x ")
        assertTrue(MarkdownRich.relayoutPictures(state))
        val chip = tokens(state).single()
        assertEquals(EditorImage.TRIGGER, chip.trigger)
        assertEquals("x 🖼 Test", state.annotatedString.text)
        assertEquals(TextUnit.Unspecified, state.toRichTextDocument().blocks[0].lineHeight)
        assertEquals("x $picture", MarkdownRich.toMarkdown(state))
        assertFalse(MarkdownRich.relayoutPictures(state))
        // Removing the text makes it a thumbnail again.
        state.removeTextRange(TextRange(0, 2))
        assertTrue(MarkdownRich.relayoutPictures(state))
        assertEquals(EditorImage.THUMB_TRIGGER, tokens(state).single().trigger)
        assertEquals(EditorImage.THUMB_LABEL, state.annotatedString.text)
        assertEquals(picture, MarkdownRich.toMarkdown(state))
    }

    @Test
    fun theCaretFollowsTheRelabelledText() {
        val state = MarkdownRich.toRich("**bold** $picture")
        state.selection = TextRange(state.annotatedString.text.length)
        // Deleting "bold" leaves the picture in a paragraph with text: still a chip, same Markdown.
        assertFalse(MarkdownRich.relayoutPictures(state))
        val alone = MarkdownRich.toRich(picture)
        alone.selection = TextRange(1)
        alone.addTextAtIndex(1, "z")
        assertTrue(MarkdownRich.relayoutPictures(alone))
        assertEquals(
            alone.annotatedString.text.length,
            alone.selection.end.coerceAtMost(alone.annotatedString.text.length),
        )
        assertEquals("$picture" + "z", MarkdownRich.toMarkdown(alone))
    }

    @Test
    fun aNewParagraphAfterAThumbnailGetsANormalLine() {
        val state = MarkdownRich.toRich(picture)
        state.addTextAtIndex(1, "\n")
        MarkdownRich.relayoutPictures(state)
        val blocks = state.toRichTextDocument().blocks
        assertEquals(EditorImage.THUMB_TRIGGER, tokens(state).single().trigger)
        assertTrue(blocks.drop(1).all { it.lineHeight == TextUnit.Unspecified })
        assertEquals(MarkdownRich.DEFAULT_THUMB_LINE_HEIGHT, blocks[0].lineHeight)
    }

    @Test
    fun aHalfDeletedThumbnailIsStillRemovedWhole() {
        val state = MarkdownRich.toRich("a\n\n$picture\n\nb")
        val at = MarkdownRich.thumbnails(state).single().offset
        state.removeTextRange(TextRange(at, at + 1))
        assertTrue(MarkdownRich.dropDamagedPictures(state) || tokens(state).isEmpty())
        assertEquals("a\n\nb", MarkdownRich.toMarkdown(state).replace(Regex("\n{3,}"), "\n\n"))
    }

    @Test
    fun picturesRoundTripWhetherThumbnailOrChip() {
        listOf(
                picture,
                "Intro\n\n$picture\n\nOutro",
                "![a](/media/1.jpg)\n\n![b](/media/2.jpg){.sn-size-xl}",
                "- ![a](/media/1.jpg)\n- text ![b](/media/2.jpg)",
                "### Heading\n\n![h](/media/h.jpg)",
                "Before $picture after",
            )
            .forEach { assertEquals(it, MarkdownRich.roundTrip(it, false)) }
    }

    @Test
    fun theBoxFitsThePictureIntoTheLine() {
        // 160 px line, 4 px padding: 152 px of height; widths are capped by the size annotation.
        val wide = ThumbnailGeometry.fit(1000f, 160f, 4f, Size(2000f, 1000f), MarkdownImageSize.XL)
        assertEquals(Size(304f, 152f), wide)
        val narrow =
            ThumbnailGeometry.fit(1000f, 160f, 4f, Size(2000f, 1000f), MarkdownImageSize.SM)
        assertEquals(Size(304f, 152f), narrow)
        val capped = ThumbnailGeometry.fit(400f, 160f, 4f, Size(2000f, 1000f), MarkdownImageSize.SM)
        assertEquals(Size(220f, 110f), capped)
        val tall = ThumbnailGeometry.fit(1000f, 160f, 4f, Size(500f, 2000f), null)
        assertEquals(Size(38f, 152f), tall)
        // Unknown size (loading, offline): a 4:3 placeholder.
        val placeholder = ThumbnailGeometry.fit(1000f, 160f, 4f, null, MarkdownImageSize.MD)
        assertEquals(Size(152f * 4f / 3f, 152f), placeholder)
        // No room: nothing negative.
        assertEquals(Size(0f, 0f), ThumbnailGeometry.fit(0f, 6f, 4f, null, null))
    }

    @Test
    fun selectionAndCaretNextToAThumbnail() {
        assertTrue(ThumbnailGeometry.touches(TextRange(5), 5))
        assertTrue(ThumbnailGeometry.touches(TextRange(6), 5))
        assertTrue(ThumbnailGeometry.touches(TextRange(2, 9), 5))
        assertFalse(ThumbnailGeometry.touches(TextRange(4), 5))
        assertFalse(ThumbnailGeometry.touches(TextRange(7), 5))
        assertTrue(ThumbnailGeometry.caretBeside(TextRange(5), 5))
        assertTrue(ThumbnailGeometry.caretBeside(TextRange(6), 5))
        assertFalse(ThumbnailGeometry.caretBeside(TextRange(5, 6), 5))
        assertEquals(TextRange(5, 6), ThumbnailGeometry.selectionOf(5))
    }

    @Test
    fun aStrayThumbnailCharacterIsRemoved() {
        // An IME's undo can put the thumbnail character back as plain text, without its token.
        val state = MarkdownRich.toRich("a\n\nb")
        state.addTextAtIndex(0, EditorImage.THUMB_LABEL)
        assertTrue(MarkdownRich.dropDamagedPictures(state))
        assertEquals("a\n\nb", MarkdownRich.toMarkdown(state))
        assertFalse(MarkdownRich.dropDamagedPictures(state))
        val real = MarkdownRich.toRich(picture)
        assertFalse(MarkdownRich.dropDamagedPictures(real))
        assertEquals(picture, MarkdownRich.toMarkdown(real))
    }
}
