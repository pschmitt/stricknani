package blue.anika.wolle.ui.common

import blue.anika.wolle.R
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
}
