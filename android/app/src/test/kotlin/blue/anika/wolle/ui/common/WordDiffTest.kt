package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {
    private fun List<DiffSegment>.changedTexts() = filter { it.changed }.map { it.text }

    private fun List<DiffSegment>.joined() = joinToString("") { it.text }

    @Test
    fun marksOnlyTheWordsThatDiffer() {
        val diff = WordDiff.diff("ui-audit ok 4281", "ui-audit slow 6844")
        assertEquals(listOf("ok 4281"), diff.before.changedTexts())
        assertEquals(listOf("slow 6844"), diff.after.changedTexts())
        assertEquals("ui-audit ok 4281", diff.before.joined())
        assertEquals("ui-audit slow 6844", diff.after.joined())
    }

    @Test
    fun handlesPureAdditionsAndRemovals() {
        assertEquals(listOf("Fresh text"), WordDiff.diff("", "Fresh text").after.changedTexts())
        assertEquals(emptyList<DiffSegment>(), WordDiff.diff("", "Fresh text").before)
        assertEquals(listOf("b"), WordDiff.diff("a b c", "a c").before.changedTexts())
    }

    @Test
    fun keepsIdenticalTextUnmarked() {
        assertEquals(
            emptyList<String>(),
            WordDiff.diff("same words", "same words").after.changedTexts(),
        )
    }

    @Test
    fun fallsBackToWholeValuesForVeryLongTexts() {
        val long = (0 until 600).joinToString(" ") { "w$it" }
        val diff = WordDiff.diff(long, "$long x")
        assertEquals(listOf(DiffSegment(long, true)), diff.before)
    }
}
