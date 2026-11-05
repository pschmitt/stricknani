package blue.anika.wolle.data.timer

import java.time.Instant

/** Converts the server's start and sample times into a device-local reminder deadline. */
fun projectTimerReminderDeadline(
    startedAt: String,
    serverTime: String,
    intervalMinutes: Int,
    deviceNowMillis: Long,
    resetFromNow: Boolean = false,
): Long? {
    if (intervalMinutes !in ProjectTimerReminderInterval.choices || intervalMinutes == 0)
        return null
    if (resetFromNow) return deviceNowMillis + intervalMinutes * 60_000L
    val elapsedMillis =
        runCatching {
                (Instant.parse(serverTime).toEpochMilli() - Instant.parse(startedAt).toEpochMilli())
                    .coerceAtLeast(0)
            }
            .getOrNull() ?: return deviceNowMillis + intervalMinutes * 60_000L
    return deviceNowMillis + (intervalMinutes * 60_000L - elapsedMillis).coerceAtLeast(0)
}

/** True only for the same account and uninterrupted timer run that owns this due reminder. */
fun shouldShowProjectTimerReminder(
    expectedAccountKey: String,
    currentAccountKey: String,
    expectedStartedAt: String,
    currentStartedAt: String?,
    scheduledStartedAt: String?,
    intervalMinutes: Int,
    deadlineMillis: Long?,
    nowMillis: Long,
): Boolean =
    expectedAccountKey == currentAccountKey &&
        expectedStartedAt == currentStartedAt &&
        expectedStartedAt == scheduledStartedAt &&
        intervalMinutes > 0 &&
        intervalMinutes in ProjectTimerReminderInterval.choices &&
        deadlineMillis != null &&
        nowMillis >= deadlineMillis
