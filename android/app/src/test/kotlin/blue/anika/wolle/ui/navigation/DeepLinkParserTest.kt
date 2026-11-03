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
    fun `parses canonical project and yarn SPA URLs`() {
        assertEquals(
            Route.ProjectDetail(42),
            DeepLinkParser.parse("https://stricknani.example.com/app/projects/42"),
        )
        assertEquals(
            Route.YarnDetail(7),
            DeepLinkParser.parse("https://stricknani.example.com/app/yarns/7"),
        )
    }

    @Test
    fun `parses regardless of host or scheme`() {
        assertEquals(
            Route.ProjectDetail(1),
            DeepLinkParser.parse("http://192.168.1.5:8080/projects/1"),
        )
    }

    @Test
    fun `ignores trailing edit segments for legacy and SPA paths`() {
        assertEquals(
            Route.ProjectDetail(42),
            DeepLinkParser.parse("https://stricknani.example.com/projects/42/edit"),
        )
        assertEquals(
            Route.YarnDetail(7),
            DeepLinkParser.parse("https://stricknani.example.com/app/yarns/7/edit"),
        )
    }

    @Test
    fun `returns null for an unrelated path`() {
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/settings"))
    }

    @Test
    fun `parses query and fragment without changing the route`() {
        assertEquals(
            Route.ProjectDetail(42),
            DeepLinkParser.parse("https://stricknani.example.com/app/projects/42?tab=notes#step-2"),
        )
    }

    @Test
    fun `returns null for malformed zero and overflowing ids`() {
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/projects/abc"))
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/projects/0"))
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/app/yarns/2147483648"))
    }

    @Test
    fun `does not accept unrelated or arbitrary deployment prefixes`() {
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/settings/projects/42"))
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/tenant/app/yarns/7"))
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/app/yarn/7"))
        assertNull(DeepLinkParser.parse("https://stricknani.example.com/app/yarns/7extra"))
    }

    @Test
    fun `returns null for a null or blank input`() {
        assertNull(DeepLinkParser.parse(null))
        assertNull(DeepLinkParser.parse(""))
    }

    @Test
    fun `returns null for a stricknani setup QR URI - handled separately by QrConfigCodec`() {
        // SNA-61: this is deliberate, not a gap - a `stricknani://setup?p=...` URI matches neither
        // the project nor yarn path regex, so it must fall through to null here. It used to just
        // get silently discarded at that point; MainActivity now separately checks
        // `QrConfigCodec.looksLikeQrConfigUri` on the same `intent.dataString` and routes a match
        // into `OnboardingViewModel.connectFromScannedText` instead of relying on this parser.
        assertNull(DeepLinkParser.parse("stricknani://setup?p=eyJhIjoxfQ"))
    }

    @Test
    fun `ignores the app's own custom scheme links`() {
        assertNull(DeepLinkParser.parse("stricknani://oidc-callback?code=c&state=s&flow=login"))
        assertNull(DeepLinkParser.parse("stricknani://oidc-callback/projects/5"))
        assertNull(DeepLinkParser.parse("stricknani://setup?p=abc"))
    }
}
