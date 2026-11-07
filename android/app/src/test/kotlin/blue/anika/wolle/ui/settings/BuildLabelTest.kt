package blue.anika.wolle.ui.settings

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildLabelTest {
    @Test
    fun labelNamesVersionSourceAndBuildType() {
        assertEquals("v1.4.0 (latest, debug)", appVersionLabel("1.4.0", "latest", debug = true))
        assertEquals("v1.4.0 (stable, release)", appVersionLabel("1.4.0", "stable", debug = false))
        assertEquals("v1.4.0 (local, debug)", appVersionLabel("1.4.0", "local", debug = true))
        assertEquals("v1.4.0 (play, release)", appVersionLabel("1.4.0", "play", debug = false))
    }

    @Test
    fun blankSourceIsLeftOut() {
        assertEquals("v1.4.0 (release)", appVersionLabel("1.4.0", "  ", debug = false))
    }

    @Test
    fun buildDateUsesLocaleAndZone() {
        val utc = ZoneId.of("UTC")
        val en = formatBuildDate("2026-11-07T13:45:00Z", Locale.US, utc)!!
        val de = formatBuildDate("2026-11-07T13:45:00Z", Locale.GERMANY, utc)!!
        assertTrue(en, en.contains("2026") && en.contains("Nov"))
        assertTrue(de, de.contains("07.11.2026") && de.contains("13:45"))
        val berlin =
            formatBuildDate("2026-11-07T13:45:00Z", Locale.GERMANY, ZoneId.of("Europe/Berlin"))!!
        assertTrue(berlin, berlin.contains("14:45"))
    }

    @Test
    fun unusableBuildDateIsNull() {
        assertNull(formatBuildDate(null))
        assertNull(formatBuildDate(""))
        assertNull(formatBuildDate("unknown"))
        assertNull(formatBuildDate("yesterday"))
    }
}
