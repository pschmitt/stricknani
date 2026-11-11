package blue.anika.wolle.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.TrashRef
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** A localized, one-shot result emitted after a user-triggered data mutation. */
data class MutationFeedbackEvent(
    @StringRes val messageResId: Int,
    val formatArgs: List<Any> = emptyList(),
    /**
     * Set when the message says something moved to the trash: the snackbar then offers "Undo",
     * which restores this item.
     */
    val undo: TrashRef? = null,
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

    /** "Moved to the trash" with an Undo action that restores [item] (see `TrashUndoViewModel`). */
    fun showTrashed(@StringRes messageResId: Int, item: TrashRef, vararg formatArgs: Any) {
        _events.tryEmit(MutationFeedbackEvent(messageResId, formatArgs.toList(), item))
    }

    private companion object {
        const val EVENT_BUFFER_SIZE = 16
    }
}

/** Collects shared mutation events into the app-level snackbar host. */
@Composable
fun MutationFeedbackEffect(
    feedback: MutationFeedback,
    snackbarHostState: SnackbarHostState,
    onUndo: (TrashRef) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnUndo by rememberUpdatedState(onUndo)
    LaunchedEffect(feedback) {
        feedback.events.collect { event ->
            val message = context.localizedMutationMessage(event)
            val kind = context.mutationFeedbackKind(event)
            val undo = event.undo
            if (undo == null) {
                snackbarHostState.showFeedback(message = message, kind = kind)
            } else {
                // The snackbar waits for its action or dismissal; do not hold up later events.
                launch {
                    val result =
                        snackbarHostState.showFeedback(
                            message = message,
                            kind = kind,
                            actionLabel = context.getString(R.string.trash_undo),
                        )
                    if (result == SnackbarResult.ActionPerformed) currentOnUndo(undo)
                }
            }
        }
    }
}

/**
 * The catalogue already names its messages: `error_*` entries are failures and `*_queued` ones are
 * writes parked for the next sync, so the badge follows from the resource name.
 */
private fun Context.mutationFeedbackKind(event: MutationFeedbackEvent): FeedbackKind {
    val name = resources.getResourceEntryName(event.messageResId)
    return when {
        name.startsWith("error_") -> FeedbackKind.Error
        name.endsWith("_queued") -> FeedbackKind.Offline
        else -> FeedbackKind.Success
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
