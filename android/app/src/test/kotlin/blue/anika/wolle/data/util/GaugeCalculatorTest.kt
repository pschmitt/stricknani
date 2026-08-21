package blue.anika.wolle.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Test vectors mirror `tests/test_gauge.py` for parity with the backend's `calculate_gauge`. */
class GaugeCalculatorTest {

    @Test
    fun `basic calculation without rows`() {
        val result =
            GaugeCalculator.calculate(
                patternGaugeStitches = 20,
                patternGaugeRows = 26,
                userGaugeStitches = 18,
                userGaugeRows = 24,
                patternCastOnStitches = 120,
            )

        assertEquals(108, result.adjustedStitches) // 120 * 18 / 20 = 108
        assertNull(result.adjustedRows)
    }

    @Test
    fun `calculation with rows`() {
        val result =
            GaugeCalculator.calculate(
                patternGaugeStitches = 20,
                patternGaugeRows = 26,
                userGaugeStitches = 18,
                userGaugeRows = 24,
                patternCastOnStitches = 120,
                patternRowCount = 100,
            )

        assertEquals(108, result.adjustedStitches)
        assertEquals(92, result.adjustedRows) // round(100 * 24 / 26)
    }

    @Test
    fun `exact gauge match returns pattern counts unchanged`() {
        val result =
            GaugeCalculator.calculate(
                patternGaugeStitches = 20,
                patternGaugeRows = 26,
                userGaugeStitches = 20,
                userGaugeRows = 26,
                patternCastOnStitches = 80,
                patternRowCount = 50,
            )

        assertEquals(80, result.adjustedStitches)
        assertEquals(50, result.adjustedRows)
    }

    @Test
    fun `results are rounded to the nearest integer`() {
        val result =
            GaugeCalculator.calculate(
                patternGaugeStitches = 22,
                patternGaugeRows = 28,
                userGaugeStitches = 19,
                userGaugeRows = 25,
                patternCastOnStitches = 121,
                patternRowCount = 95,
            )

        assertEquals(104, result.adjustedStitches) // round(121 * 19 / 22)
        assertEquals(85, result.adjustedRows) // round(95 * 25 / 28)
    }
}
