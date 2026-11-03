package blue.anika.wolle.ui.gauge

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
}
