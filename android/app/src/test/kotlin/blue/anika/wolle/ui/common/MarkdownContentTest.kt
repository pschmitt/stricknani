package blue.anika.wolle.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownContentTest {

    @Test
    fun `normalizes a project three style html stitch sample`() {
        val content =
            """
            <p>Gauge:&nbsp;20 stitches&nbsp;×&nbsp;28 rows.</p>
            <p><img src="/media/projects/3/stitch-sample.jpg" alt="Stitch sample" /></p>
            """.trimIndent()

        val normalized = normalizeMarkdownContent(content)

        assertTrue(normalized.contains("Gauge: 20 stitches × 28 rows."))
        assertTrue(
            normalized.contains(
                "![Stitch sample](/media/projects/3/stitch-sample.jpg)",
            ),
        )
        assertFalse(normalized.contains("&nbsp;"))
        assertFalse(normalized.contains("<img"))
    }

    @Test
    fun `preserves markdown images and converts common html wrappers`() {
        val content =
            "<div><strong>Front</strong><br><img src='media/swatch.png' alt='Swatch'></div>"

        assertTrue(
            normalizeMarkdownContent(content).contains(
                "**Front**\n![Swatch](media/swatch.png)",
            ),
        )
    }

    @Test
    fun `drops malformed image tags without exposing html`() {
        val normalized = normalizeMarkdownContent("<p>Text</p><img alt='missing source'>")

        assertTrue(normalized == "Text")
        assertFalse(normalized.contains("<img"))
    }
}
