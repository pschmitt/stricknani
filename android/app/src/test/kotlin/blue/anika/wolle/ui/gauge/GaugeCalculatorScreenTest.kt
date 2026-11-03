package blue.anika.wolle.ui.gauge

import blue.anika.wolle.data.util.SwatchSize
import blue.anika.wolle.data.util.formatCm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaugeCalculatorScreenTest {

    @Test
    fun `result appears only once everything required is filled`() {
        assertNull(gaugeResultOrNull("22", "30", "20", "", "100", ""))
        assertNull(gaugeResultOrNull("22", "30", "20", "28", "0", ""))
        assertNull(gaugeResultOrNull("22", "30", "20", "28", "100", "0"))
        val result = gaugeResultOrNull("22", "30", "20", "28", "110", "")!!
        assertEquals(100, result.adjustedStitches)
        assertNull(result.adjustedRows)
        assertEquals(56, gaugeResultOrNull("22", "30", "20", "28", "110", "60")!!.adjustedRows)
    }

    @Test
    fun `differences are signed against the pattern`() {
        assertEquals("+4", signedDifference(104, 100))
        assertEquals("−3", signedDifference(97, 100))
        assertEquals("±0", signedDifference(100, 100))
    }

    @Test
    fun `swatch sizes compare densities, 10 by 10 cm by default`() {
        // 9 stitches and 12 rows over 5 x 5 cm is the same as 18 and 24 over 10 x 10 cm.
        val small =
            gaugeResultOrNull(
                "22",
                "30",
                "9",
                "12",
                "110",
                "60",
                userSwatch = SwatchSize(5.0, 5.0),
            )!!
        val default = gaugeResultOrNull("22", "30", "18", "24", "110", "60")!!
        assertEquals(default, small)
        assertEquals(90, small.adjustedStitches)
        assertEquals(48, small.adjustedRows)
    }

    @Test
    fun `ties round half up like the server`() {
        // 121 cast-on stitches at a 19/22 ratio is 104.5, which becomes 105.
        assertEquals(105, gaugeResultOrNull("22", "28", "19", "25", "121", "")!!.adjustedStitches)
    }

    @Test
    fun `swatch size parsing and formatting`() {
        assertEquals(4.123, SwatchSize.parseCm("4,12345")!!, 0.0)
        assertEquals(7.5, SwatchSize.parseCm("7,5")!!, 0.0)
        assertEquals(null, SwatchSize.parseCm("0"))
        assertEquals(null, SwatchSize.parseCm("101"))
        assertEquals("10", formatCm(10.0))
        assertEquals("7.5", formatCm(7.5))
    }
}
