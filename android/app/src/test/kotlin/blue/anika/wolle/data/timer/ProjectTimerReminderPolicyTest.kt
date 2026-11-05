package blue.anika.wolle.data.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTimerReminderPolicyTest {
    @Test
    fun `reminder deadline follows continuous run instead of lifetime accumulated time`() {
        val now = 1_000_000L
        val deadline =
            projectTimerReminderDeadline(
                startedAt = "2026-11-05T10:00:00Z",
                serverTime = "2026-11-05T10:01:00Z",
                intervalMinutes = ProjectTimerReminderInterval.HOUR_1,
                deviceNowMillis = now,
            )

        assertEquals(now + 59 * 60_000L, deadline)
        assertFalse(
            shouldShowProjectTimerReminder(
                expectedAccountKey = "account-a",
                currentAccountKey = "account-a",
                expectedStartedAt = "2026-11-05T10:00:00Z",
                currentStartedAt = "2026-11-05T10:00:00Z",
                scheduledStartedAt = "2026-11-05T10:00:00Z",
                intervalMinutes = 60,
                deadlineMillis = deadline,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `due reminder requires the same account and timer run and an enabled interval`() {
        fun shouldShow(account: String, run: String?, interval: Int = 60) =
            shouldShowProjectTimerReminder(
                expectedAccountKey = "account-a",
                currentAccountKey = account,
                expectedStartedAt = "2026-11-05T10:00:00Z",
                currentStartedAt = run,
                scheduledStartedAt = "2026-11-05T10:00:00Z",
                intervalMinutes = interval,
                deadlineMillis = 50L,
                nowMillis = 50L,
            )

        assertTrue(shouldShow("account-a", "2026-11-05T10:00:00Z"))
        assertFalse(shouldShow("account-b", "2026-11-05T10:00:00Z"))
        assertFalse(shouldShow("account-a", "2026-11-05T11:00:00Z"))
        assertFalse(shouldShow("account-a", null))
        assertFalse(shouldShow("account-a", "2026-11-05T10:00:00Z", interval = 0))
    }

    @Test
    fun `editing total during the same timestamp second resets reminder from the edit`() {
        val now = 1_000_000L
        val sameStartedAt = "2026-11-05T10:00:00Z"
        val deadline =
            projectTimerReminderDeadline(
                startedAt = sameStartedAt,
                serverTime = "2026-11-05T11:00:00Z",
                intervalMinutes = ProjectTimerReminderInterval.HOUR_1,
                deviceNowMillis = now,
                resetFromNow = true,
            )

        assertEquals(now + 60 * 60_000L, deadline)
    }
}
