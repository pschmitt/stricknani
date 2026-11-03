package blue.anika.wolle.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Test vectors match the Rust server's `POST /api/v2/tools/gauge` (half-up rounding). */
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

        assertEquals(105, result.adjustedStitches) // 121 * 19 / 22 = 104.5, half up
        assertEquals(85, result.adjustedRows) // round(95 * 25 / 28)
    }

    @Test
    fun `ties round half up like the server`() {
        fun stitches(cast: Int, user: Int, pattern: Int) =
            GaugeCalculator.calculate(pattern, 1, user, 1, cast).adjustedStitches

        assertEquals(3, stitches(5, 1, 2)) // 2.5
        assertEquals(4, stitches(7, 1, 2)) // 3.5
        assertEquals(105, stitches(121, 19, 22)) // 104.5
    }

    @Test
    fun `swatch sizes compare densities`() {
        // 9 x 12 over 5 x 5 cm equals 18 x 24 over 10 x 10 cm: 80 * 18 / 20, 100 * 24 / 28.
        val small =
            GaugeCalculator.calculate(
                20,
                28,
                9,
                12,
                80,
                100,
                userSwatch = SwatchSize(5.0, 5.0),
            )
        assertEquals(72, small.adjustedStitches)
        assertEquals(86, small.adjustedRows)

        val wide = GaugeCalculator.calculate(20, 28, 22, 30, 80, 100, SwatchSize(20.0, 10.0))
        assertEquals(176, wide.adjustedStitches) // 80 * 22 * 20 / (20 * 10)
        assertEquals(107, wide.adjustedRows)
    }

    @Test
    fun `decimal swatch sizes are exact`() {
        // 1 * 5 * 10 / (2 * 12.5) = 2 exactly; rows 1 * 5 * 10 / (2 * 4) = 6.25 -> 6.
        val result =
            GaugeCalculator.calculate(
                2,
                2,
                5,
                5,
                1,
                1,
                userSwatch = SwatchSize(12.5, 4.0),
            )
        assertEquals(2, result.adjustedStitches)
        assertEquals(6, result.adjustedRows)
        // 1 * 3 * 5 / (2 * 5) = 1.5 -> 2 on a tie at non-default sizes
        val tie =
            GaugeCalculator.calculate(
                2,
                2,
                3,
                3,
                1,
                1,
                SwatchSize(5.0, 5.0),
                SwatchSize(5.0, 5.0),
            )
        assertEquals(2, tie.adjustedStitches)
        assertEquals(2, tie.adjustedRows)
    }

    @Test
    fun `huge counts do not overflow`() {
        val result = GaugeCalculator.calculate(1, 1, Int.MAX_VALUE, 1, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, result.adjustedStitches)
    }
}
