package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTextFieldTest {
    @Test
    fun `a web link shows its domain without www`() {
        assertEquals(
            "garnstudio.com",
            urlHostOrNull("https://www.garnstudio.com/pattern.php?id=3491"),
        )
        assertEquals("example.org", urlHostOrNull("  http://example.org  "))
    }

    @Test
    fun `anything that is not an http(s) link with a host is invalid`() {
        assertNull(urlHostOrNull("garnstudio.com/pattern"))
        assertNull(urlHostOrNull("ftp://example.org"))
        assertNull(urlHostOrNull("https://"))
        assertNull(urlHostOrNull("https://nodot"))
        assertNull(urlHostOrNull("not a url"))
        assertNull(urlHostOrNull(""))
    }
}
