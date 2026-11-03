package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCardModelTest {
    @Test
    fun `yarn meta joins brand and colourway`() {
        assertEquals("Drops · Sky blue", yarnMetaLine("Drops", "Sky blue", "Details to add"))
    }

    @Test
    fun `yarn meta with only one part shows it alone`() {
        assertEquals("Drops", yarnMetaLine("Drops", null, "Details to add"))
        assertEquals("Sky blue", yarnMetaLine(" ", "Sky blue", "Details to add"))
    }

    @Test
    fun `yarn meta falls back to the placeholder when empty`() {
        assertEquals("Details to add", yarnMetaLine(null, null, "Details to add"))
        assertEquals("Details to add", yarnMetaLine("", "  ", "Details to add"))
    }

    @Test
    fun `chips are capped at three`() {
        assertEquals(listOf("a", "b", "c"), libraryChips(listOf("a", "b", "c", "d")))
    }

    @Test
    fun `chips drop blanks and duplicates before capping`() {
        assertEquals(listOf("a", "b", "c"), libraryChips(listOf("a", " ", "a", "b", "c", "d")))
    }

    @Test
    fun `yarn chip is the weight category when set`() {
        assertEquals(listOf("DK"), yarnChips("DK"))
        assertEquals(emptyList<String>(), yarnChips(null))
        assertEquals(emptyList<String>(), yarnChips(""))
    }
}
