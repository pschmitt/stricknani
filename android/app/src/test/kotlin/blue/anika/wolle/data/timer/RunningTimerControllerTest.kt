package blue.anika.wolle.data.timer

import blue.anika.wolle.data.api.v2.ProjectTimerDto
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class RunningTimerControllerTest {
    private class FakeRegistry : RunningTimerRegistry {
        val data = mutableMapOf<String, MutableMap<Int, RegisteredTimer>>()

        override suspend fun all(accountKey: String): Map<Int, RegisteredTimer> =
            data[accountKey]?.toMap() ?: emptyMap()

        override suspend fun put(accountKey: String, projectId: Int, timer: RegisteredTimer) {
            data.getOrPut(accountKey) { mutableMapOf() }[projectId] = timer
        }

        override suspend fun remove(accountKey: String, projectId: Int) {
            data[accountKey]?.remove(projectId)
        }

        override suspend fun clear() = data.clear()
    }

    /** The server: timers by project; failures can be injected per call. */
    private class FakeGateway : ProjectTimerGateway {
        val timers = mutableMapOf<Int, ProjectTimerDto>()
        val stopCalls = mutableListOf<Int>()
        var failure: Exception? = null

        override suspend fun get(projectId: Int): ProjectTimerDto {
            failure?.let { throw it }
            return timers.getValue(projectId)
        }

        override suspend fun stop(projectId: Int): ProjectTimerDto {
            failure?.let { throw it }
            stopCalls += projectId
            val stopped = timers.getValue(projectId).copy(startedAt = null)
            timers[projectId] = stopped
            return stopped
        }
    }

    private class RecordingSink : RunningTimerSink {
        val shown = mutableMapOf<Int, RunningTimerNotification>()
        var cancelAllCalls = 0

        override fun show(notification: RunningTimerNotification) {
            shown[notification.projectId] = notification
        }

        override fun cancel(projectId: Int) {
            shown.remove(projectId)
        }

        override fun cancelAll() {
            cancelAllCalls++
            shown.clear()
        }
    }

    private val gateway = FakeGateway()
    private val registry = FakeRegistry()
    private val sink = RecordingSink()
    private var enabled = true
    private var account: String? = "acct"
    private var userId: Long? = ME
    private val clock = 1_000_000_000L

    private val controller =
        RunningTimerController(
            gateway,
            registry,
            sink,
            { enabled },
            { account },
            { userId },
            { clock },
        )

    private fun running(total: Long = 600, startedBy: Long? = ME) =
        ProjectTimerDto(total, "2026-11-09T10:00:00Z", "2026-11-09T10:10:00Z", startedBy)

    private fun stopped(total: Long = 600) = ProjectTimerDto(total, null, "2026-11-09T10:10:00Z")

    private fun http(code: Int) =
        HttpException(
            Response.error<Any>(code, "".toResponseBody("application/json".toMediaType()))
        )

    @Test
    fun `a running timer gets a notification titled with the project and counting its total`() =
        runTest {
            controller.onTimerObserved(7, "Socks", canEdit = true, timer = running(600))
            val shown = sink.shown.getValue(7)
            assertEquals("Socks", shown.projectName)
            assertEquals(clock - 600_000L, shown.baseMillis)
            assertEquals("acct", shown.accountKey)
            assertEquals(RegisteredTimer("Socks"), registry.all("acct")[7])
        }

    @Test
    fun `each running timer has its own notification keyed by project`() = runTest {
        controller.onTimerObserved(1, "A", true, running())
        controller.onTimerObserved(2, "B", true, running())
        assertEquals(setOf(1, 2), sink.shown.keys)
        assertEquals("running_timer_1", runningTimerTag(1))
        assertTrue(runningTimerTag(1) != runningTimerTag(2))
        // The reminder notifications use the prefix "project_timer_": no overlap.
        assertFalse(runningTimerTag(1).startsWith("project_timer_"))
    }

    @Test
    fun `a stopped timer removes its notification and its registration`() = runTest {
        controller.onTimerObserved(7, "Socks", true, running())
        controller.onTimerObserved(7, "Socks", true, stopped())
        assertTrue(sink.shown.isEmpty())
        assertTrue(registry.all("acct").isEmpty())
    }

    @Test
    fun `no notification when timers are off on this device or the user cannot edit`() = runTest {
        enabled = false
        controller.onTimerObserved(7, "Socks", true, running())
        assertTrue(sink.shown.isEmpty())
        enabled = true
        controller.onTimerObserved(7, "Socks", canEdit = false, timer = running())
        assertTrue(sink.shown.isEmpty())
        assertTrue(registry.all("acct").isEmpty())
    }

    @Test
    fun `no notification without a signed-in account`() = runTest {
        account = null
        controller.onTimerObserved(7, "Socks", true, running())
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun `refresh re-posts running timers and drops those stopped elsewhere`() = runTest {
        registry.put("acct", 1, RegisteredTimer("A"))
        registry.put("acct", 2, RegisteredTimer("B"))
        gateway.timers[1] = running(60)
        gateway.timers[2] = stopped()
        assertEquals(1, controller.refreshAll())
        assertEquals(setOf(1), sink.shown.keys)
        assertEquals("A", sink.shown.getValue(1).projectName)
        assertEquals(setOf(1), registry.all("acct").keys)
    }

    @Test
    fun `refresh keeps notifications on network errors and drops gone projects`() = runTest {
        registry.put("acct", 1, RegisteredTimer("A"))
        gateway.timers[1] = running()
        gateway.failure = IOException("offline")
        assertEquals(1, controller.refreshAll())
        assertTrue(sink.shown.isEmpty()) // nothing new was posted ...
        assertEquals(setOf(1), registry.all("acct").keys) // ... and nothing was forgotten
        gateway.failure = http(404)
        assertEquals(0, controller.refreshAll())
        assertTrue(registry.all("acct").isEmpty())
    }

    @Test
    fun `refresh with timers turned off clears everything`() = runTest {
        controller.onTimerObserved(7, "Socks", true, running())
        enabled = false
        assertEquals(0, controller.refreshAll())
        assertTrue(sink.shown.isEmpty())
        assertTrue(registry.all("acct").isEmpty())
        assertEquals(1, sink.cancelAllCalls)
    }

    @Test
    fun `a dismissed notification is not re-posted by the check but returns with the app`() =
        runTest {
            controller.onTimerObserved(7, "Socks", true, running())
            gateway.timers[7] = running()
            controller.onDismissed(7)
            sink.cancel(7)
            assertEquals(1, controller.refreshAll())
            assertTrue(sink.shown.isEmpty())
            controller.onTimerObserved(7, "Socks", true, running())
            assertEquals(setOf(7), sink.shown.keys)
        }

    @Test
    fun `the stop action stops the timer through the repository and removes the notification`() =
        runTest {
            gateway.timers[7] = running()
            controller.onTimerObserved(7, "Socks", true, gateway.timers.getValue(7))
            val reloads = mutableListOf<Int>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    controller.stopped.collect { reloads += it }
                }
            assertEquals(StopTimerResult.Stopped, controller.stop(7, "acct"))
            assertEquals(listOf(7), gateway.stopCalls)
            assertTrue(sink.shown.isEmpty())
            assertTrue(registry.all("acct").isEmpty())
            assertEquals(listOf(7), reloads) // open screens reload
            job.cancel()
        }

    @Test
    fun `the stop action does nothing for another account or with timers off`() = runTest {
        gateway.timers[7] = running()
        controller.onTimerObserved(7, "Socks", true, gateway.timers.getValue(7))
        assertEquals(StopTimerResult.Ignored, controller.stop(7, "someone else"))
        enabled = false
        assertEquals(StopTimerResult.Ignored, controller.stop(7, "acct"))
        assertTrue(gateway.stopCalls.isEmpty())
        assertEquals(setOf(7), sink.shown.keys)
    }

    @Test
    fun `the stop action keeps the notification and asks for a retry when offline`() = runTest {
        gateway.timers[7] = running()
        controller.onTimerObserved(7, "Socks", true, gateway.timers.getValue(7))
        gateway.failure = IOException("offline")
        assertEquals(StopTimerResult.Retry, controller.stop(7, "acct"))
        assertEquals(setOf(7), sink.shown.keys)
        gateway.failure = http(500)
        assertEquals(StopTimerResult.Retry, controller.stop(7, "acct"))
        gateway.failure = http(403)
        assertEquals(StopTimerResult.Gone, controller.stop(7, "acct"))
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun `a stop of an already stopped timer is idempotent`() = runTest {
        gateway.timers[7] = stopped()
        assertEquals(StopTimerResult.Stopped, controller.stop(7, "acct"))
        assertFalse(controller.hasRunning())
    }

    @Test
    fun `a timer someone else started gets no notification here`() = runTest {
        // The owner, another editor or a viewer sees the run but is not told about it.
        controller.onTimerObserved(7, "Socks", true, running(startedBy = OTHER))
        assertTrue(sink.shown.isEmpty())
        assertTrue(registry.all("acct").isEmpty())
        // A run the server cannot attribute (started before it recorded the starter) is nobody's.
        controller.onTimerObserved(7, "Socks", true, running(startedBy = null))
        assertTrue(sink.shown.isEmpty())
        // A signed-in user whose id is not known yet is nobody either.
        userId = null
        controller.onTimerObserved(7, "Socks", true, running())
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun `the notification goes away when another user takes over a new run`() = runTest {
        controller.onTimerObserved(7, "Socks", true, running())
        assertEquals(setOf(7), sink.shown.keys)
        // Someone else stopped it and started a new run: that run is theirs.
        controller.onTimerObserved(7, "Socks", true, running(startedBy = OTHER))
        assertTrue(sink.shown.isEmpty())
        assertTrue(registry.all("acct").isEmpty())
    }

    @Test
    fun `the check drops a timer that another user restarted`() = runTest {
        registry.put("acct", 1, RegisteredTimer("A"))
        registry.put("acct", 2, RegisteredTimer("B"))
        gateway.timers[1] = running(60)
        gateway.timers[2] = running(60, startedBy = OTHER)
        assertEquals(1, controller.refreshAll())
        assertEquals(setOf(1), sink.shown.keys)
        assertEquals(setOf(1), registry.all("acct").keys)
    }

    @Test
    fun `only the user who started the run is its starter`() {
        assertTrue(isTimerStarter(running(), ME))
        assertFalse(isTimerStarter(running(), OTHER))
        assertFalse(isTimerStarter(running(), null))
        assertFalse(isTimerStarter(running(startedBy = null), ME))
        assertFalse(isTimerStarter(stopped(), ME))
        assertTrue(shouldShowRunningTimer(true, true, running(), ME))
        assertFalse(shouldShowRunningTimer(true, true, running(startedBy = OTHER), ME))
        assertFalse(shouldShowRunningTimer(true, false, running(), ME))
        assertFalse(shouldShowRunningTimer(false, true, running(), ME))
    }

    @Test
    fun `base time is clamped for negative totals`() {
        assertEquals(1000L, runningTimerBaseMillis(1000L, -5))
        assertEquals(0L, runningTimerBaseMillis(5_000L, 5))
    }
}

/** The signed-in user, and someone else who shares the project. */
private const val ME = 5L
private const val OTHER = 9L
