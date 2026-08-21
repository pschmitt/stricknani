package blue.anika.wolle.ui.common

import blue.anika.wolle.R
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutationFeedbackTest {
    @Test
    fun eventsKeepLocalizedResourceAndFormatArgumentsAcrossNavigation() = runTest {
        val feedback = MutationFeedback()
        val nextEvent = async(start = CoroutineStart.UNDISPATCHED) { feedback.events.first() }

        feedback.show(R.string.mutation_project_created_queued, "ignored-format-argument")

        val event = nextEvent.await()
        assertEquals(R.string.mutation_project_created_queued, event.messageResId)
        assertEquals(listOf("ignored-format-argument"), event.formatArgs)
    }

    @Test
    fun eventBusCarriesSuccessQueuedOfflineAndErrorOutcomes() = runTest {
        val feedback = MutationFeedback()
        val nextEvents =
            async(start = CoroutineStart.UNDISPATCHED) { feedback.events.take(4).toList() }

        feedback.show(R.string.mutation_favorite_added)
        feedback.show(R.string.mutation_project_updated_queued)
        feedback.show(R.string.mutation_favorite_offline)
        feedback.show(R.string.error_delete_failed)

        assertEquals(
            listOf(
                R.string.mutation_favorite_added,
                R.string.mutation_project_updated_queued,
                R.string.mutation_favorite_offline,
                R.string.error_delete_failed,
            ),
            nextEvents.await().map { it.messageResId },
        )
    }

    @Test
    fun offlineDetectionWalksTheCauseChain() {
        assertTrue(IOException("offline").isOfflineFailure())
        assertTrue(IllegalStateException(IOException("offline")).isOfflineFailure())
        assertFalse(IllegalStateException("server rejected request").isOfflineFailure())
    }
}
