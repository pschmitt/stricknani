package blue.anika.wolle.ui.common

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditTest {
    private fun field(text: String, start: Int = text.length, end: Int = start) =
        TextFieldValue(text, TextRange(start, end))

    private fun TextFieldValue.apply(format: MarkdownFormat) = applyMarkdownFormat(this, format)

    @Test
    fun boldWrapsTheSelection() {
        val out = field("a word here", 2, 6).apply(MarkdownFormat.BOLD)
        assertEquals("a **word** here", out.text)
        assertEquals(TextRange(4, 8), out.selection)
    }

    @Test
    fun boldTwiceRemovesTheMarkers() {
        val once = field("a word here", 2, 6).apply(MarkdownFormat.BOLD)
        val twice = once.apply(MarkdownFormat.BOLD)
        assertEquals("a word here", twice.text)
        assertEquals(TextRange(2, 6), twice.selection)
    }

    @Test
    fun boldWithoutSelectionPlacesTheCursorBetweenTheMarkers() {
        val out = field("ab", 1).apply(MarkdownFormat.BOLD)
        assertEquals("a****b", out.text)
        assertEquals(TextRange(3), out.selection)
        assertEquals("ab", out.apply(MarkdownFormat.BOLD).text)
    }

    @Test
    fun italicDoesNotTreatBoldAsItalic() {
        val out = field("**x**", 2, 3).apply(MarkdownFormat.ITALIC)
        assertEquals("***x***", out.text)
    }

    @Test
    fun italicWrapsAndUnwraps() {
        val out = field("slip", 0, 4).apply(MarkdownFormat.ITALIC)
        assertEquals("*slip*", out.text)
        assertEquals("slip", out.apply(MarkdownFormat.ITALIC).text)
    }

    @Test
    fun selectedMarkersAreUnwrapped() {
        val out = field("**slip**", 0, 8).apply(MarkdownFormat.BOLD)
        assertEquals("slip", out.text)
        assertEquals(TextRange(0, 4), out.selection)
    }

    @Test
    fun headingTogglesOnTheCurrentLine() {
        val out = field("Gauge\nWork even", 2).apply(MarkdownFormat.HEADING)
        assertEquals("### Gauge\nWork even", out.text)
        assertEquals("Gauge\nWork even", out.apply(MarkdownFormat.HEADING).text)
    }

    @Test
    fun headingReplacesAnotherLevel() {
        val out = field("# Title", 3).apply(MarkdownFormat.HEADING)
        assertEquals("### Title", out.text)
    }

    @Test
    fun bulletListPrefixesEverySelectedLine() {
        val out = field("one\ntwo\nthree", 0, 13).apply(MarkdownFormat.BULLET_LIST)
        assertEquals("- one\n- two\n- three", out.text)
        assertEquals("one\ntwo\nthree", out.apply(MarkdownFormat.BULLET_LIST).text)
    }

    @Test
    fun numberedListCountsAndConvertsBullets() {
        val bullets = field("- one\n- two", 0, 11)
        val out = bullets.apply(MarkdownFormat.NUMBERED_LIST)
        assertEquals("1. one\n2. two", out.text)
        assertEquals("one\ntwo", out.apply(MarkdownFormat.NUMBERED_LIST).text)
    }

    @Test
    fun listOnlyTouchesTheSelectedLines() {
        val out = field("a\nb\nc", 2).apply(MarkdownFormat.BULLET_LIST)
        assertEquals("a\n- b\nc", out.text)
    }

    @Test
    fun listOnEmptyFieldInsertsThePrefix() {
        val out = field("").apply(MarkdownFormat.BULLET_LIST)
        assertEquals("- ", out.text)
        assertEquals(TextRange(2), out.selection)
    }

    @Test
    fun linkWrapsTheSelectionAndSelectsTheAddress() {
        val out = field("see pattern now", 4, 11).apply(MarkdownFormat.LINK)
        assertEquals("see [pattern](https://) now", out.text)
        assertEquals("https://", out.text.substring(out.selection.start, out.selection.end))
    }

    @Test
    fun linkWithoutSelectionInsertsAnEmptyLink() {
        val out = field("x", 1).apply(MarkdownFormat.LINK)
        assertEquals("x[](https://)", out.text)
        assertEquals(TextRange(2), out.selection)
    }

    @Test
    fun selectedAddressBecomesTheLinkTarget() {
        val out = field("https://example.com", 0, 19).apply(MarkdownFormat.LINK)
        assertEquals("[](https://example.com)", out.text)
        assertEquals(TextRange(1), out.selection)
    }

    @Test
    fun umlautsSurviveFormatting() {
        val out = field("Füße über Äste", 0, 4).apply(MarkdownFormat.BOLD)
        assertEquals("**Füße** über Äste", out.text)
    }

    @Test
    fun lineFieldsKeepEveryLineBreakInThePreview() {
        assertEquals(
            "2 buttons  \nmarkers\n\ncable",
            markdownPreviewSource("2 buttons\nmarkers\n\ncable", true),
        )
        assertEquals("a\nb", markdownPreviewSource("a\nb", false))
    }
}
