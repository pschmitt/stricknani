package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same expectations as the web's `import-progress.test.ts`. */
class ImportProgressTest {
    @Test
    fun `a link import walks through its steps and stays on the last`() {
        val steps = importSteps(ai = true)
        assertEquals(0, currentImportStep(steps, 0))
        assertEquals(1, currentImportStep(steps, 6_000))
        assertEquals(2, currentImportStep(steps, 20_000))
        assertEquals(3, currentImportStep(steps, 10 * 60_000L))
    }

    @Test
    fun `it is quicker without AI`() {
        assertEquals(2, currentImportStep(importSteps(ai = false), 3_500))
        assertEquals(0, currentImportStep(importSteps(ai = true), 3_500))
    }

    @Test
    fun `the bar never fills before the result`() {
        assertEquals(0f, importProgressFraction(0, ai = true))
        assertTrue(importProgressFraction(30_000, ai = true) > 0.5f)
        assertTrue(importProgressFraction(60 * 60_000L, ai = true) <= 0.95f)
    }

    @Test
    fun `the elapsed time reads like a clock`() {
        assertEquals("0:07", formatImportElapsed(7_400))
        assertEquals("1:23", formatImportElapsed(83_000))
    }
}
