package blue.anika.wolle.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlEntitiesTest {

    @Test
    fun `decodes named and numeric entities without exposing nbsp`() {
        assertEquals("Gauge: 20 × 28 – 100%", decodeHtmlEntities("Gauge:&nbsp;20 &times; 28 &#8211; 100%"))
        assertEquals("A © B", decodeHtmlEntities("A &copy; B"))
        assertEquals("snowman ☃", decodeHtmlEntities("snowman &#x2603;"))
    }

    @Test
    fun `keeps unknown entities intact`() {
        assertEquals("&madeup;", decodeHtmlEntities("&madeup;"))
    }
}
