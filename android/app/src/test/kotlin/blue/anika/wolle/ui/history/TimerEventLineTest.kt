package blue.anika.wolle.ui.history

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ChangeItemDto
import blue.anika.wolle.data.api.dto.ChangeSetDto
import blue.anika.wolle.ui.admin.auditActionPresentation
import blue.anika.wolle.ui.admin.changeFieldLabelRes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TimerEventLineTest {
    private fun seconds(value: Int): JsonElement = buildJsonObject {
        put("type", "integer")
        put("value", value)
    }

    private fun changes(before: Int, after: Int) =
        ChangeSetDto(
            items = listOf(ChangeItemDto(TIMER_SECONDS_FIELD, seconds(before), seconds(after)))
        )

    @Test
    fun `a length of knitting time reads like the website`() {
        assertEquals("< 1 min", formatTimerDuration(0))
        assertEquals("< 1 min", formatTimerDuration(59))
        assertEquals("25 min", formatTimerDuration(25 * 60 + 30))
        assertEquals("1 h", formatTimerDuration(3600))
        assertEquals("3 h 10 min", formatTimerDuration(3 * 3600 + 10 * 60))
        assertEquals("< 1 min", formatTimerDuration(-5))
    }

    @Test
    fun `stopping names the length of the run`() {
        val line = timerEventLine("timer_stopped", changes(600, 2100))
        assertEquals(TimerEventLine(R.string.history_timer_stopped_after, "25 min"), line)
    }

    @Test
    fun `editing names the new total`() {
        val line = timerEventLine("timer_total_changed", changes(600, 11400))
        assertEquals(TimerEventLine(R.string.history_timer_total_changed_to, "3 h 10 min"), line)
    }

    @Test
    fun `starting needs no details and the others fall back until they are loaded`() {
        assertEquals(
            TimerEventLine(R.string.history_timer_started, null),
            timerEventLine("timer_started", null),
        )
        assertEquals(
            TimerEventLine(R.string.history_timer_stopped, null),
            timerEventLine("timer_stopped", null),
        )
        assertEquals(
            TimerEventLine(R.string.history_timer_total_changed, null),
            timerEventLine("timer_total_changed", ChangeSetDto()),
        )
    }

    @Test
    fun `other actions have no timer line`() {
        assertNull(timerEventLine("updated", changes(1, 2)))
        assertNull(timerEventLine("shared", null))
    }

    @Test
    fun `the admin audit knows the timer actions and the field`() {
        for (action in listOf("timer_started", "timer_stopped", "timer_total_changed")) {
            assertNotNull(action, auditActionPresentation(action).labelRes)
        }
        assertEquals(R.string.admin_field_timer_seconds, changeFieldLabelRes(TIMER_SECONDS_FIELD))
    }
}
