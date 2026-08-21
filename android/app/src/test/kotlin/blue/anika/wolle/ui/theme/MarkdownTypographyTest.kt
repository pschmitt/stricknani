package blue.anika.wolle.ui.theme

import androidx.compose.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTypographyTest {

    @Test
    fun `markdown headings use compact material title scale`() {
        val typography = Typography()
        val headings = markdownHeadingStyles(typography)

        assertEquals(typography.headlineSmall.fontSize, headings.h1.fontSize)
        assertTrue(headings.h1.fontSize < typography.displayLarge.fontSize)
        assertEquals(typography.titleLarge.fontSize, headings.h2.fontSize)
        assertEquals(typography.titleMedium.fontSize, headings.h3.fontSize)
    }

    @Test
    fun `heading levels remain ordered through body and label styles`() {
        val headings = markdownHeadingStyles(Typography())

        assertTrue(headings.h1.fontSize > headings.h2.fontSize)
        assertTrue(headings.h2.fontSize > headings.h3.fontSize)
        assertTrue(headings.h3.fontSize > headings.h4.fontSize)
        assertTrue(headings.h4.fontSize > headings.h5.fontSize)
        assertTrue(headings.h5.fontSize > headings.h6.fontSize)
    }
}
