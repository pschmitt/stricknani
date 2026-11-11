package blue.anika.wolle.ui.common

import blue.anika.wolle.R
import blue.anika.wolle.data.repository.TrashKind
import blue.anika.wolle.data.repository.TrashRef
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

    @Test
    fun trashedMessagesCarryTheItemToRestoreOnUndo() = runTest {
        val feedback = MutationFeedback()
        val nextEvents =
            async(start = CoroutineStart.UNDISPATCHED) { feedback.events.take(2).toList() }

        feedback.showTrashed(
            R.string.mutation_moved_to_trash,
            TrashRef(TrashKind.Project, 7),
        )
        feedback.show(R.string.mutation_trash_restored)

        val (trashed, restored) = nextEvents.await()
        assertEquals(TrashUndo(listOf(TrashRef("project", 7))), trashed.undo)
        assertEquals("projects", trashed.undo?.items?.single()?.segment)
        assertEquals(null, restored.undo)
    }

    @Test
    fun savedRemovalsCarryExactlyTheRemovedItemsAndQueueTheRestore() = runTest {
        val feedback = MutationFeedback()
        val nextEvents =
            async(start = CoroutineStart.UNDISPATCHED) { feedback.events.take(1).toList() }
        val removed = listOf(TrashRef(TrashKind.Step, 11), TrashRef(TrashKind.Image, 5))

        feedback.showTrashedBySave(R.string.mutation_saved_removed_to_trash, emptyList())
        feedback.showTrashedBySave(R.string.mutation_saved_removed_to_trash, removed)

        val event = nextEvents.await().single()
        assertEquals(TrashUndo(removed, viaQueue = true), event.undo)
        assertEquals(R.string.mutation_saved_removed_to_trash, event.messageResId)
    }
}
