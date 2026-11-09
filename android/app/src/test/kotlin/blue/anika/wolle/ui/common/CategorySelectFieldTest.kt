package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySelectFieldTest {
    @Test
    fun `options are sorted by name without duplicates or blanks`() {
        assertEquals(
            listOf("blankets", "Socks"),
            categoryOptions(listOf("Socks", "blankets", " ", "SOCKS"), ""),
        )
    }

    @Test
    fun `a current category that is not listed stays selectable`() {
        assertEquals(listOf("Hats", "Socks"), categoryOptions(listOf("Socks"), "Hats"))
        assertEquals(listOf("Socks"), categoryOptions(listOf("Socks"), "socks"))
    }

    @Test
    fun `the selected option matches the current value ignoring case`() {
        assertEquals("Socks", selectedCategoryOption(listOf("Socks"), "socks"))
        assertEquals("", selectedCategoryOption(listOf("Socks"), " "))
    }
}
