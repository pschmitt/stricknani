package blue.anika.wolle.ui.palette

import blue.anika.wolle.ui.palette.PaletteMatcher.Field
import blue.anika.wolle.ui.palette.PaletteMatcher.Kind
import blue.anika.wolle.ui.palette.PaletteMatcher.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteMatcherTest {
    @Test
    fun `fold lowers case and drops accents without changing the length`() {
        assertEquals("kase ol uber", PaletteMatcher.fold("Käse Öl Über"))
        assertEquals("strase", PaletteMatcher.fold("Straße"))
        assertEquals("Crème brûlée".length, PaletteMatcher.fold("Crème brûlée").length)
    }

    @Test
    fun `a query is split into folded words`() {
        assertEquals(listOf("wolle", "grun"), PaletteMatcher.queryWords("  Wolle  Grün "))
        assertEquals(emptyList<String>(), PaletteMatcher.queryWords("   "))
    }

    @Test
    fun `exact beats prefix beats word start beats substring beats fuzzy`() {
        val exact = PaletteMatcher.matchWord("socks", "socks")!!
        val prefix = PaletteMatcher.matchWord("sock", "socks")!!
        val word = PaletteMatcher.matchWord("sock", "warm socks")!!
        val sub = PaletteMatcher.matchWord("ock", "socks")!!
        val fuzzy = PaletteMatcher.matchWord("scs", "socks")!!
        val scores = listOf(exact, prefix, word, sub, fuzzy).map { it.score }
        assertEquals(scores.sortedDescending(), scores)
        assertEquals(5, scores.toSet().size)
        assertEquals(Kind.Fuzzy, fuzzy.kind)
    }

    @Test
    fun `short or out of order letters do not match fuzzily`() {
        assertNull(PaletteMatcher.matchWord("sk", "socks"))
        assertNull(PaletteMatcher.matchWord("kcs", "socks"))
    }

    @Test
    fun `match ranges are the highlighted characters`() {
        assertEquals(listOf(Range(5, 9)), PaletteMatcher.matchWord("sock", "warm socks")!!.ranges)
        assertEquals(
            listOf(Range(0, 1), Range(3, 4), Range(4, 5)),
            PaletteMatcher.matchWord("sks", "socks")!!.ranges,
        )
    }

    private val fields = listOf(Field("Müller Pullover", 1.0), Field("Jumpers", 0.6))

    @Test
    fun `umlauts match when typed without them`() {
        assertNotNull(PaletteMatcher.scoreEntry(listOf("muller"), fields))
    }

    @Test
    fun `every word must be found`() {
        assertNull(PaletteMatcher.scoreEntry(listOf("pullover", "zzz"), fields))
        assertNotNull(PaletteMatcher.scoreEntry(listOf("pullover", "jump"), fields))
    }

    @Test
    fun `the title counts more than the line below`() {
        val title = PaletteMatcher.scoreEntry(listOf("pullover"), fields)!!
        val sub = PaletteMatcher.scoreEntry(listOf("jump"), fields)!!
        assertTrue(title.score > sub.score)
        assertEquals(listOf(Range(7, 15)), title.ranges[0])
        assertEquals(listOf(Range(0, 4)), sub.ranges[1])
    }

    @Test
    fun `a blank query scores zero`() {
        assertEquals(0.0, PaletteMatcher.scoreEntry(emptyList(), fields)!!.score, 0.0)
    }

    @Test
    fun `touching ranges are merged`() {
        assertEquals(
            listOf(Range(0, 2), Range(3, 4)),
            PaletteMatcher.mergeRanges(listOf(Range(3, 4), Range(0, 1), Range(1, 2))),
        )
    }

    @Test
    fun `detectUrl accepts an http or https link`() {
        assertEquals(
            "https://example.com/pattern?id=3",
            PaletteMatcher.detectUrl("https://example.com/pattern?id=3"),
        )
        assertEquals("http://example.org/a", PaletteMatcher.detectUrl("  http://example.org/a  "))
    }

    @Test
    fun `detectUrl rejects everything else`() {
        assertNull(PaletteMatcher.detectUrl("example.com"))
        assertNull(PaletteMatcher.detectUrl("ftp://example.com"))
        assertNull(PaletteMatcher.detectUrl("https://example.com is nice"))
        assertNull(PaletteMatcher.detectUrl("javascript:alert(1)"))
        assertNull(PaletteMatcher.detectUrl("https://"))
        assertNull(PaletteMatcher.detectUrl(""))
    }
}
