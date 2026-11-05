package blue.anika.wolle.data.timer

import blue.anika.wolle.data.oidc.errorCodeOf
import java.time.Instant

enum class ProjectTimerLoadFailure {
    UnsupportedServer,
    ProjectMissing,
    RequestFailed,
}

/**
 * Only a bare 404 means the timer route is missing; a structured 404 can mean the project is gone.
 */
fun classifyProjectTimerLoadFailure(status: Int?, errorBody: String?): ProjectTimerLoadFailure =
    when {
        status != 404 -> ProjectTimerLoadFailure.RequestFailed
        errorBody.isNullOrBlank() -> ProjectTimerLoadFailure.UnsupportedServer
        errorCodeOf(errorBody) == "not_found" -> ProjectTimerLoadFailure.ProjectMissing
        else -> ProjectTimerLoadFailure.RequestFailed
    }

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
    featureEnabled: Boolean = true,
): Boolean =
    featureEnabled &&
        expectedAccountKey == currentAccountKey &&
        expectedStartedAt == currentStartedAt &&
        expectedStartedAt == scheduledStartedAt &&
        intervalMinutes > 0 &&
        intervalMinutes in ProjectTimerReminderInterval.choices &&
        deadlineMillis != null &&
        nowMillis >= deadlineMillis
