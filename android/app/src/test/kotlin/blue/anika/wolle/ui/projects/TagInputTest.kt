package blue.anika.wolle.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class TagInputTest {
    @Test
    fun `splits on commas only so multi-word tags stay whole`() {
        assertEquals(listOf("winter gift", "socks"), parseTagInput("winter gift, socks"))
    }

    @Test
    fun `drops blanks and case-insensitive duplicates`() {
        assertEquals(listOf("Socks", "wool"), parseTagInput(" Socks ,, socks, wool ,"))
        assertEquals(emptyList<String>(), parseTagInput("  "))
    }
}
