package blue.anika.wolle.data.backups

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class RestartResult {
    SUCCEEDED,
    FAILED,
    TIMEOUT,
}

/** What `GET /api/v2/meta` said after the restart. */
data class MetaProbe(
    val epoch: String?,
    val lastStatus: String?,
    val lastFinishedMs: Long?,
    /** 401: the token is not in the data the server now has (a restore replaced the sign-ins). */
    val unauthorized: Boolean = false,
)

/** The two requests the poll makes; faked in tests. */
interface RestartProbe {
    /** True when `/healthz` answers 200; false (or a thrown error) when the server is down. */
    suspend fun healthy(): Boolean

    /** The meta answer, or null when it could not be read. */
    suspend fun meta(): MetaProbe?
}

/**
 * Polls `/healthz` and `/api/v2/meta` until the restarted server says what happened to the restore:
 * a changed data epoch (or a newer "succeeded" outcome) is success, a newer "failed" outcome is a
 * failure. A server that never went down is still the old process, so its state does not count. A
 * bearer token the restored data does not know (401) also means the data was replaced. Mirrors
 * `waitForRestart` in `backups-api.ts`.
 */
suspend fun waitForRestart(
    probe: RestartProbe,
    previousEpoch: String?,
    requestedAtMs: Long,
    timeoutMs: Long = 3 * 60 * 1000,
    intervalMs: Long = 1500,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): RestartResult {
    val deadline = now() + timeoutMs
    var sawDown = false
    while (now() < deadline) {
        val healthy =
            try {
                probe.healthy()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
        if (!healthy) {
            sawDown = true
        } else if (sawDown) {
            val meta = probe.meta()
            if (meta != null) {
                if (meta.unauthorized) return RestartResult.SUCCEEDED
                val outcomeIsNew =
                    meta.lastFinishedMs != null && meta.lastFinishedMs >= requestedAtMs - 5000
                if (
                    (meta.epoch != null && previousEpoch != null && meta.epoch != previousEpoch) ||
                        (outcomeIsNew && meta.lastStatus == "succeeded")
                )
                    return RestartResult.SUCCEEDED
                // Back up with nothing (or a failure) recorded: the restore was not applied.
                return RestartResult.FAILED
            }
        }
        sleep(intervalMs)
    }
    return RestartResult.TIMEOUT
}

/** An RFC 3339 instant as epoch milliseconds, or null when it does not parse. */
fun parseInstantOrNull(value: String?): Long? = value?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
}
