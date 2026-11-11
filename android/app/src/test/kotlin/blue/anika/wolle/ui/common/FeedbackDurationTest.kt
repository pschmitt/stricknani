package blue.anika.wolle.ui.common

import androidx.compose.material3.SnackbarDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackDurationTest {
    @Test
    fun plainConfirmationsAreShort() {
        assertEquals(SnackbarDuration.Short, feedbackDuration(FeedbackKind.Success, false))
        assertEquals(SnackbarDuration.Short, feedbackDuration(FeedbackKind.Info, false))
    }

    @Test
    fun messagesWithAnActionStayLonger() {
        assertEquals(SnackbarDuration.Long, feedbackDuration(FeedbackKind.Success, true))
        assertEquals(SnackbarDuration.Long, feedbackDuration(FeedbackKind.Info, true))
    }

    @Test
    fun queuedOfflineNotesLeaveOnTheirOwn() {
        assertEquals(SnackbarDuration.Long, feedbackDuration(FeedbackKind.Offline, false))
    }

    @Test
    fun onlyErrorsStayUntilDismissed() {
        assertEquals(SnackbarDuration.Indefinite, feedbackDuration(FeedbackKind.Error, false))
        assertEquals(SnackbarDuration.Indefinite, feedbackDuration(FeedbackKind.Error, true))
        assertEquals(
            SnackbarDuration.Long,
            FeedbackVisuals("Project created.", FeedbackKind.Offline).duration,
        )
    }
}
