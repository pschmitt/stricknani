package blue.anika.wolle.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class TagInputTest {
    @Test
    fun `a comma ends a tag`() {
        assertEquals(listOf("test", "test2"), parseTagInput("test, test2"))
    }

    @Test
    fun `a space ends a tag too`() {
        assertEquals(listOf("winter", "gift", "socks"), parseTagInput("winter gift, socks"))
        assertEquals(listOf("a", "b", "c"), parseTagInput("a, b c"))
    }

    @Test
    fun `drops blanks and case-insensitive duplicates`() {
        assertEquals(listOf("Socks", "wool"), parseTagInput(" Socks ,, socks, wool ,"))
        assertEquals(emptyList<String>(), parseTagInput("  "))
        assertEquals(emptyList<String>(), parseTagInput(" , ,"))
    }
}
