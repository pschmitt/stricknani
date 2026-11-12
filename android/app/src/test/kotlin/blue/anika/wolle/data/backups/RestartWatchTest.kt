package blue.anika.wolle.data.backups

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors the "waiting for the restart" block of `backups-api.test.ts`. */
class RestartWatchTest {
    /** One scripted step per poll: the server is down, or up with this meta answer. */
    private sealed interface Step {
        data object Down : Step

        data class Up(val meta: MetaProbe?) : Step
    }

    private class ScriptedProbe(private val steps: List<Step>) : RestartProbe {
        private var index = 0

        private val step: Step
            get() = steps[minOf(index, steps.size - 1)]

        override suspend fun healthy(): Boolean {
            val current = step
            if (current is Step.Down) {
                index += 1
                return false
            }
            return true
        }

        override suspend fun meta(): MetaProbe? {
            val current = step as Step.Up
            index += 1
            return current.meta
        }
    }

    private val requestedAt = 1_800_000_000_000L

    private fun up(
        epoch: String?,
        status: String? = null,
        finishedMs: Long? = null,
        unauthorized: Boolean = false,
    ) = Step.Up(MetaProbe(epoch, status, finishedMs, unauthorized))

    private suspend fun wait(
        vararg steps: Step,
        timeoutMs: Long = 3 * 60 * 1000,
        clock: () -> Long = { 0L },
    ) =
        waitForRestart(
            ScriptedProbe(steps.toList()),
            previousEpoch = "old",
            requestedAtMs = requestedAt,
            timeoutMs = timeoutMs,
            intervalMs = 1,
            now = clock,
            sleep = {},
        )

    @Test
    fun `reports success when the epoch changed after the server came back`() = runTest {
        assertEquals(
            RestartResult.SUCCEEDED,
            wait(Step.Down, Step.Down, up("new")),
        )
    }

    @Test
    fun `reports a failed restore from the recorded outcome`() = runTest {
        assertEquals(
            RestartResult.FAILED,
            wait(Step.Down, up("old", "failed", requestedAt + 5_000)),
        )
    }

    @Test
    fun `does not trust the old process before it went down`() = runTest {
        var ticks = 0L
        assertEquals(
            RestartResult.TIMEOUT,
            wait(up("new"), timeoutMs = 5, clock = { ticks++ }),
        )
    }

    @Test
    fun `treats a restart that recorded nothing as a failure`() = runTest {
        assertEquals(RestartResult.FAILED, wait(Step.Down, up("old")))
    }

    @Test
    fun `ignores an outcome from an earlier restore`() = runTest {
        assertEquals(
            RestartResult.FAILED,
            wait(Step.Down, up("old", "succeeded", 1_577_836_800_000L)),
        )
    }

    @Test
    fun `a newer succeeded outcome counts even with an unchanged epoch`() = runTest {
        assertEquals(
            RestartResult.SUCCEEDED,
            wait(Step.Down, up("old", "succeeded", requestedAt + 1_000)),
        )
    }

    @Test
    fun `a token the restored data does not know means the data was replaced`() = runTest {
        assertEquals(
            RestartResult.SUCCEEDED,
            wait(Step.Down, up(null, unauthorized = true)),
        )
    }

    @Test
    fun `keeps polling while the meta answer cannot be read`() = runTest {
        assertEquals(
            RestartResult.SUCCEEDED,
            wait(Step.Down, Step.Up(null), up("new")),
        )
    }

    @Test
    fun `parses instants`() {
        assertEquals(1_767_232_800_000L, parseInstantOrNull("2026-01-01T02:00:00Z"))
        assertEquals(null, parseInstantOrNull("nope"))
        assertEquals(null, parseInstantOrNull(null))
    }
}
