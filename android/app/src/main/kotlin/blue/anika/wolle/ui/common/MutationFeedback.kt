package blue.anika.wolle.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A localized, one-shot result emitted after a user-triggered data mutation. */
data class MutationFeedbackEvent(
    @StringRes val messageResId: Int,
    val formatArgs: List<Any> = emptyList(),
)

/**
 * Process-wide mutation result bus. ViewModels emit resource IDs rather than rendered text so the
 * active app locale is applied when the root snackbar renders the event, even after navigation.
 */
@Singleton
class MutationFeedback @Inject constructor() {
    private val _events =
        MutableSharedFlow<MutationFeedbackEvent>(
            extraBufferCapacity = EVENT_BUFFER_SIZE,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<MutationFeedbackEvent> = _events.asSharedFlow()

    fun show(@StringRes messageResId: Int, vararg formatArgs: Any) {
        _events.tryEmit(MutationFeedbackEvent(messageResId, formatArgs.toList()))
    }

    private companion object {
        const val EVENT_BUFFER_SIZE = 16
    }
}

/** Collects shared mutation events into the app-level snackbar host. */
@Composable
fun MutationFeedbackEffect(feedback: MutationFeedback, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(feedback) {
        feedback.events.collect { event ->
            snackbarHostState.showSnackbar(
                message = context.localizedMutationMessage(event),
                duration = SnackbarDuration.Short,
            )
        }
    }
}

private fun Context.localizedMutationMessage(event: MutationFeedbackEvent): String =
    getString(event.messageResId, *event.formatArgs.toTypedArray())

/** Network failures are reported separately because queued writes remain safe to retry offline. */
fun Throwable.isOfflineFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return true
        current = current.cause
    }
    return false
}
