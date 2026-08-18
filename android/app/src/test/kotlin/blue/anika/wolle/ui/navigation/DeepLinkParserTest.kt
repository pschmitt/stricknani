package blue.anika.wolle.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParserTest {

    @Test
    fun `parses a project URL`() {
        assertEquals(
            Route.ProjectDetail(42),
            DeepLinkParser.parse("https://stricknani.example.com/projects/42"),
        )
    }

    @Test
    fun `parses a yarn URL`() {
        assertEquals(
            Route.YarnDetail(7),
            DeepLinkParser.parse("https://stricknani.example.com/yarn/7"),
        )
    }

    @Test
    fun `parses regardless of host or scheme`() {
        assertEquals(Route.ProjectDetail(1), DeepLinkParser.parse("http://192.168.1.5:8080/projects/1"))
    }

    @Test
    fun `ignores a trailing path segment`() {
        assertEquals(
            Route.ProjectDetail(42),
            DeepLinkParser.parse("https://stricknani.example.com/projects/42/edit"),
        )
    }

    @Test
    fun `returns null for an unrelated path`() {
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/settings"))
    }

    @Test
    fun `returns null for a non-numeric id`() {
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/projects/abc"))
    }

    @Test
    fun `returns null for a null or blank input`() {
        assertNull(DeepLinkParser.parse(null))
        assertNull(DeepLinkParser.parse(""))
    }
}
