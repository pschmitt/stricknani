@file:OptIn(ExperimentalRichTextApi::class)

package blue.anika.wolle.ui.common

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRichTest {
    private fun roundTrip(markdown: String, keep: Boolean = false) =
        MarkdownRich.roundTrip(markdown, keep)

    /** Texts the visual editor carries unchanged: the Markdown comes back as written. */
    @Test
    fun plainFormattingSurvivesUnchanged() {
        listOf(
                "Just a line of text.",
                "First paragraph.\n\nSecond paragraph.",
                "Some **bold** and *italic* and ***both*** words.",
                "### Heading\n\nBody text",
                "# One\n\n## Two\n\ntext",
                "- one\n- two\n- three",
                "1. one\n2. two\n3. three",
                "- **1. Runde:** 8 Maschen anschlagen\n- **2. Runde:** alle Maschen rechts",
                "See [the pattern](https://example.com/x?a=1&b=2) now.",
                "Schöne Grüße, Maße: 5 mm – größer als 40 cm. Äpfel & Birnen",
                "Intro:\n\n- a\n- b\n\nOutro",
                "Materials:\n\n1. Yarn\n2. Needles\n\nNote **this**.",
                "some ~~gone~~ text",
                "use `code` here",
                "a < b and I <3 yarn",
                "*k2, p2* to end",
            )
            .forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun blankTextIsAlwaysEditable() {
        assertEquals("", roundTrip(""))
        assertEquals("", roundTrip("  \n "))
    }

    /** Spellings that are written differently but render the same are normalized. */
    @Test
    fun equivalentSpellingsAreNormalized() {
        assertEquals("- one\n- two", roundTrip("* one\n* two"))
        assertEquals(
            "- one\n  - nested a\n  - nested b\n- two",
            roundTrip("- one\n  - nested a\n  - nested b\n- two"),
        )
        assertEquals("1. one\n   1. sub\n2. two", roundTrip("1. one\n   1. sub\n2. two"))
        assertEquals("Intro:\n\n- a\n- b", roundTrip("Intro:\n- a\n- b"))
        assertEquals("a\n\nb", roundTrip("a\n\n\nb"))
        assertEquals("text", roundTrip("text\n"))
        assertEquals("snake\\_case and *em* here", roundTrip("snake_case and _em_ here"))
        assertEquals("1. first\n2. second", roundTrip("1) first\n2) second"))
    }

    @Test
    fun nestedListsKeepTheirStructure() {
        val html = MarkdownRich.renderedHtml(roundTrip("- one\n  - nested a\n- two")!!, false)
        assertEquals(
            "<body><ul><li>one<ul><li>nested a</li></ul></li><li>two</li></ul></body>",
            html,
        )
    }

    /** What the library cannot carry stays in Raw. */
    @Test
    fun unsupportedTextStaysRaw() {
        listOf(
                "| a | b |\n|---|---|\n| 1 | 2 |",
                "[![a cat](/media/cat.jpg)](https://example.com)",
                "> quoted",
                "```\ncode\n```",
                "<b>html</b>",
                "[x] done",
                "---",
            )
            .forEach { assertNull(it, roundTrip(it)) }
    }

    @Test
    fun aHardBreakInProseBecomesANewParagraph() {
        assertEquals("line one\n\nline two", roundTrip("line one  \nline two"))
    }

    @Test
    fun lineFieldsKeepEveryLineBreak() {
        val text = "2 buttons\nmarkers\n\ncable"
        assertEquals(text, roundTrip(text, keep = true))
    }

    private fun typed(text: String): String {
        val state = RichTextState()
        state.addTextAfterSelection(text)
        val markdown = MarkdownRich.toMarkdown(state)
        // Reading it back gives the typed characters, and the field can stay visual.
        assertEquals(text, MarkdownRich.toRich(markdown).toText())
        assertEquals(markdown, roundTrip(markdown))
        return markdown
    }

    /** Characters that mean something in Markdown are escaped, and come back as typed. */
    @Test
    fun typedMarkdownCharactersAreEscaped() {
        assertEquals("5 \\* 3 = 15", typed("5 * 3 = 15"))
        assertEquals("\\* k2, p2 \\* repeat", typed("* k2, p2 * repeat"))
        assertEquals("a \\_x\\_ b", typed("a _x_ b"))
        assertEquals("\\# not a heading", typed("# not a heading"))
        assertEquals("\\[a\\] and \\[b\\](c)", typed("[a] and [b](c)"))
        assertEquals("back\\\\slash \\`tick\\` \\~", typed("back\\slash `tick` ~"))
        assertEquals("1\\. not a list", typed("1. not a list"))
        assertEquals("\\- not a bullet", typed("- not a bullet"))
        assertEquals("a < b, I <3 yarn", typed("a < b, I <3 yarn"))
    }

    @Test
    fun escapesInLoadedMarkdownBecomeLiterals() {
        assertEquals(
            "price * 3 and 1. not list",
            MarkdownRich.toRich("price \\* 3 and 1\\. not list").toText(),
        )
        assertEquals("a_b", MarkdownRich.toRich("a\\_b").toText())
        // A backslash inside a code span stays.
        assertEquals("a\\*b", MarkdownRich.toRich("`a\\*b`").toText())
        assertEquals("price \\* 3", roundTrip("price \\* 3"))
    }

    @Test
    fun strayDelimitersInOldTextStayLiteral() {
        assertEquals(
            "Rnd 1: \\* k2, p2 \\* repeat",
            roundTrip("Rnd 1: * k2, p2 * repeat"),
        )
        assertEquals(
            "Rnd 1: * k2, p2 * repeat",
            MarkdownRich.toRich("Rnd 1: * k2, p2 * repeat").toText(),
        )
        assertEquals("5 \\* 3 = 15 and a\\_b\\_c", roundTrip("5 * 3 = 15 and a_b_c"))
    }

    @Test
    fun toolbarCommandsWriteMarkdown() {
        val state = MarkdownRich.toRich("hello world")
        state.selection = TextRange(6, 11)
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        assertEquals("hello **world**", MarkdownRich.toMarkdown(state))
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        assertEquals("hello world", MarkdownRich.toMarkdown(state))

        state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        assertEquals("hello *world*", MarkdownRich.toMarkdown(state))

        val heading = MarkdownRich.toRich("Title")
        heading.selection = TextRange(0)
        heading.setHeadingStyle(HeadingStyle.H3)
        assertEquals("### Title", MarkdownRich.toMarkdown(heading))
        heading.setHeadingStyle(HeadingStyle.Normal)
        assertEquals("Title", MarkdownRich.toMarkdown(heading))

        val bullets = MarkdownRich.toRich("one")
        bullets.selection = TextRange(0)
        bullets.toggleUnorderedList()
        assertEquals("- one", MarkdownRich.toMarkdown(bullets))

        val numbers = MarkdownRich.toRich("one")
        numbers.selection = TextRange(0)
        numbers.toggleOrderedList()
        assertEquals("1. one", MarkdownRich.toMarkdown(numbers))

        val link = MarkdownRich.toRich("see pattern")
        link.addLinkToTextRange("https://example.com", TextRange(4, 11))
        assertEquals("see [pattern](https://example.com)", MarkdownRich.toMarkdown(link))
    }

    @Test
    fun linkTargetsGetAScheme() {
        assertEquals("https://example.com", normalizeLinkTarget(" example.com "))
        assertEquals("http://example.com", normalizeLinkTarget("http://example.com"))
        assertEquals("mailto:a@b.c", normalizeLinkTarget("mailto:a@b.c"))
        assertEquals("/media/x", normalizeLinkTarget("/media/x"))
        assertEquals("", normalizeLinkTarget("   "))
    }

    @Test
    fun softWrappedProseIsOneParagraphAndStaysVisual() {
        val source = "106 M anschlagen\n1 Reihe rechts\n3 cm Rippenmuster"
        assertTrue(MarkdownRich.canEditVisually(source, keepLineBreaks = false))
        assertEquals(
            "106 M anschlagen 1 Reihe rechts 3 cm Rippenmuster",
            MarkdownRich.toMarkdown(MarkdownRich.toRich(source), keepLineBreaks = false),
        )
    }

    @Test
    fun nonBreakingSpaceLinesStayVisualAndAreWrittenBack() {
        val source = "Gauge\n\n&nbsp;\n\n![Swatch](/media/a.jpg)\n\n&nbsp;"
        assertTrue(MarkdownRich.canEditVisually(source, keepLineBreaks = true))
        assertTrue(MarkdownRich.canEditVisually(source, keepLineBreaks = false))
    }

    @Test
    fun trailingSpacesBeforeAParagraphEndDoNotBlockVisual() {
        assertTrue(MarkdownRich.canEditVisually("9 Maschen aufgenommen \n\n(7 neu)", false))
    }
}
