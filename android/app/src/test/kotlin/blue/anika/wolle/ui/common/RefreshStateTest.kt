package blue.anika.wolle.ui.common

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
        assertEquals(RefreshState.Refreshing, controller.state.value)

        completion.complete(true)
        advanceUntilIdle()

        assertEquals(RefreshState.Finished(changed = true), controller.state.value)
    }

    @Test
    fun `successful refresh reports when nothing changed`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { false })
        advanceUntilIdle()

        assertEquals(RefreshState.Finished(changed = false), controller.state.value)
    }

    @Test
    fun `io failure is reported as offline`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { throw UnknownHostException("offline") })
        advanceUntilIdle()

        assertEquals(RefreshState.Offline, controller.state.value)
    }

    @Test
    fun `other failures are reported as errors`() = runTest {
        val controller = RefreshController(this)

        assertTrue(controller.refresh { throw IllegalStateException("server failed") })
        advanceUntilIdle()

        assertEquals(RefreshState.Error, controller.state.value)
    }
}
