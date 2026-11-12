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

        feedback.show(R.string.mutation_project_created, "ignored-format-argument")

        val event = nextEvent.await()
        assertEquals(R.string.mutation_project_created, event.messageResId)
        assertEquals(listOf("ignored-format-argument"), event.formatArgs)
    }

    @Test
    fun eventBusCarriesSuccessQueuedOfflineAndErrorOutcomes() = runTest {
        val feedback = MutationFeedback()
        val nextEvents =
            async(start = CoroutineStart.UNDISPATCHED) { feedback.events.take(4).toList() }

        feedback.show(R.string.mutation_favorite_added)
        feedback.show(R.string.mutation_project_updated)
        feedback.show(R.string.mutation_favorite_offline)
        feedback.show(R.string.error_delete_failed)

        assertEquals(
            listOf(
                R.string.mutation_favorite_added,
                R.string.mutation_project_updated,
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

    @Test
    fun onlineSaveIsASuccessNotAnOfflineNote() {
        val event =
            savedFeedbackEvent(
                offline = false,
                savedResId = R.string.mutation_project_created,
                queuedResId = R.string.mutation_saved_offline,
            )

        assertEquals(R.string.mutation_project_created, event.messageResId)
        assertEquals(FeedbackKind.Success, event.kind)
    }

    @Test
    fun offlineSaveIsAQueuedNoteWithTheOfflineWording() {
        val event =
            savedFeedbackEvent(
                offline = true,
                savedResId = R.string.mutation_project_created,
                queuedResId = R.string.mutation_saved_offline,
            )

        assertEquals(R.string.mutation_saved_offline, event.messageResId)
        assertEquals(FeedbackKind.Offline, event.kind)
    }

    @Test
    fun showSavedFollowsTheConnectivityAtTheTimeOfTheSave() = runTest {
        val feedback = MutationFeedback()
        val nextEvents =
            async(start = CoroutineStart.UNDISPATCHED) { feedback.events.take(2).toList() }

        feedback.isOffline = { false }
        feedback.showSaved(R.string.mutation_yarn_updated)
        feedback.isOffline = { true }
        feedback.showSaved(R.string.mutation_yarn_updated)

        assertEquals(
            listOf(FeedbackKind.Success, FeedbackKind.Offline),
            nextEvents.await().map { it.kind },
        )
    }

    @Test
    fun namesNeverDecideQueuedOnlyErrorsAndOfflineRefusals() {
        assertEquals(FeedbackKind.Success, defaultFeedbackKind("mutation_project_created"))
        assertEquals(FeedbackKind.Success, defaultFeedbackKind("old_thing_queued"))
        assertEquals(FeedbackKind.Error, defaultFeedbackKind("error_save_failed"))
        assertEquals(FeedbackKind.Offline, defaultFeedbackKind("mutation_favorite_offline"))
    }

    @Test
    fun deletionsCarryTheTrashKindAndRestoresTheirOwn() = runTest {
        val feedback = MutationFeedback()
        val nextEvents =
            async(start = CoroutineStart.UNDISPATCHED) { feedback.events.take(4).toList() }

        feedback.showTrashed(R.string.mutation_moved_to_trash, TrashRef(TrashKind.Project, 7))
        feedback.showTrashedBySave(
            R.string.mutation_saved_removed_to_trash,
            listOf(TrashRef(TrashKind.Project, 8)),
        )
        feedback.showPurged(R.string.mutation_trash_purged)
        feedback.showRestored(R.string.mutation_trash_restored)

        val kinds = nextEvents.await().map { it.kind }
        assertEquals(
            listOf(
                FeedbackKind.Deleted,
                FeedbackKind.Deleted,
                FeedbackKind.Purged,
                FeedbackKind.Restored,
            ),
            kinds,
        )
        assertEquals(PhosphorIcons.Trash, feedbackIcon(FeedbackKind.Deleted))
        assertEquals(PhosphorIcons.Trash, feedbackIcon(FeedbackKind.Purged))
        assertEquals(PhosphorIcons.ClockCounterClockwise, feedbackIcon(FeedbackKind.Restored))
        assertEquals(PhosphorIcons.CloudSlash, feedbackIcon(FeedbackKind.Offline))
        assertEquals(FeedbackIcons.Check, feedbackIcon(FeedbackKind.Success))
        assertEquals(FeedbackIcons.Exclamation, feedbackIcon(FeedbackKind.Error))
    }
}
