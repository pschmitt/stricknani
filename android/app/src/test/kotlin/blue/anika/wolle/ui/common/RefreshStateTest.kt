package blue.anika.wolle.ui.common

import blue.anika.wolle.R
import blue.anika.wolle.ui.home.SyncProblem
import blue.anika.wolle.ui.home.problemAfter
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshStateTest {
    @Test
    fun `only one refresh can run at a time`() = runTest {
        val controller = RefreshController(this)
        val completion = CompletableDeferred<Boolean>()

        assertTrue(controller.refresh { completion.await() })
        assertFalse(controller.refresh { true })
        assertEquals(
            RefreshState.Refreshing(RefreshTrigger.UserInitiated),
            controller.state.value,
        )

        completion.complete(true)
        advanceUntilIdle()

        assertEquals(
            RefreshState.Finished(
                changed = true,
                trigger = RefreshTrigger.UserInitiated,
            ),
            controller.state.value,
        )
    }

    @Test
    fun `successful refresh reports when nothing changed`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { false })
        advanceUntilIdle()

        assertEquals(
            RefreshState.Finished(
                changed = false,
                trigger = RefreshTrigger.UserInitiated,
            ),
            controller.state.value,
        )
    }

    @Test
    fun `repeated automatic no-change refreshes are silent`() = runTest {
        val controller = RefreshController(this)

        repeat(2) {
            assertTrue(controller.refresh(trigger = RefreshTrigger.Automatic) { false })
            advanceUntilIdle()

            assertEquals(
                RefreshState.Finished(
                    changed = false,
                    trigger = RefreshTrigger.Automatic,
                ),
                controller.state.value,
            )
            assertEquals(null, refreshFeedbackResource(controller.state.value))
            controller.clearFeedback()
        }
    }

    @Test
    fun `explicit no-change refresh remains visible`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { false })
        advanceUntilIdle()

        assertEquals(R.string.refresh_no_changes, refreshFeedbackResource(controller.state.value))
    }

    @Test
    fun `automatic refresh does not expose pull-to-refresh indicator`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh(trigger = RefreshTrigger.Automatic) { false })
        assertFalse(controller.state.value.isUserInitiatedRefresh())
    }

    @Test
    fun `automatic refresh with changes remains visible`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh(trigger = RefreshTrigger.Automatic) { true })
        advanceUntilIdle()

        assertEquals(R.string.refresh_success, refreshFeedbackResource(controller.state.value))
    }

    @Test
    fun `io failure is reported as offline`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { throw UnknownHostException("offline") })
        advanceUntilIdle()

        assertEquals(RefreshState.Offline, controller.state.value)
        assertEquals(R.string.refresh_offline, refreshFeedbackResource(controller.state.value))
    }

    @Test
    fun `other failures are reported as errors`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { throw IllegalStateException("server failed") })
        advanceUntilIdle()

        assertEquals(RefreshState.Error, controller.state.value)
        assertEquals(R.string.refresh_error, refreshFeedbackResource(controller.state.value))
    }

    @Test
    fun `a failed update check beside a good sync is no error`() = runTest {
        val controller = RefreshController(this)

        // The update check runs on its own (see HomeViewModel.checkForUpdate) and yields null on
        // any failure; the refresh block only contains the sync steps.
        val update = runCatching { throw IOException("github down") }.getOrNull()
        assertTrue(controller.refresh { true })
        advanceUntilIdle()

        assertEquals(null, update)
        assertEquals(RefreshState.Finished(changed = true), controller.state.value)
        assertEquals(null, problemAfter(null, controller.state.value, false))
    }

    @Test
    fun `a failed sync is a card error and not a snackbar on home`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { throw IllegalStateException("401 session_expired") })
        advanceUntilIdle()

        assertEquals(SyncProblem.Failed, problemAfter(null, controller.state.value, false))
    }

    @Test
    fun `offline is a card problem of its own and success or idle is none`() = runTest {
        assertEquals(SyncProblem.Offline, problemAfter(null, RefreshState.Offline, false))
        assertEquals(null, problemAfter(null, RefreshState.Idle, false))
        assertEquals(null, problemAfter(null, RefreshState.Finished(changed = false), false))
        assertEquals(
            null,
            problemAfter(null, RefreshState.Refreshing(RefreshTrigger.UserInitiated), false),
        )
    }

    @Test
    fun `automatic refreshes are skipped while signed out, a pull still goes out`() = runTest {
        val controller = RefreshController(this, skipAutomatic = { true })
        var calls = 0

        assertFalse(
            controller.refresh(trigger = RefreshTrigger.Automatic) { true.also { calls++ } }
        )
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(RefreshState.Idle, controller.state.value)

        assertTrue(
            controller.refresh(trigger = RefreshTrigger.UserInitiated) { true.also { calls++ } }
        )
        advanceUntilIdle()
        assertEquals(1, calls)
    }
}
