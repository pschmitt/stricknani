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
import androidx.compose.ui.res.stringResource
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

/**
 * What Undo restores: [items] taken to the trash. [viaQueue] is for removals the editors queued
 * with the form: they may not have reached the server yet, so the restore is queued behind them
 * (the write queue replays in order) instead of being sent right away.
 */
data class TrashUndo(val items: List<TrashRef>, val viaQueue: Boolean = false)

/** A localized, one-shot result emitted after a user-triggered data mutation. */
data class MutationFeedbackEvent(
    @StringRes val messageResId: Int,
    val formatArgs: List<Any> = emptyList(),
    /**
     * Set when the message says something moved to the trash: the snackbar then offers "Undo",
     * which restores this item.
     */
    val undo: TrashUndo? = null,
    /** What the message is about; null derives it from the message ([defaultFeedbackKind]). */
    val kind: FeedbackKind? = null,
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

    /** True while the device has no working internet; replaceable in tests. */
    internal var isOffline: () -> Boolean = { NetworkStatus.hasValidatedInternet == false }

    /** A plain confirmation, or an error when the message is an `error_*` one. */
    fun show(@StringRes messageResId: Int, vararg formatArgs: Any) {
        _events.tryEmit(MutationFeedbackEvent(messageResId, formatArgs.toList()))
    }

    /**
     * A write that was stored locally and sent right away: a confirmation, "[savedResId]". Only
     * when the device is offline is it really parked for the next sync, and then it says so
     * ([queuedResId]) with the offline kind. The later sync is silent.
     */
    fun showSaved(
        @StringRes savedResId: Int,
        @StringRes queuedResId: Int = R.string.mutation_saved_offline,
    ) {
        _events.tryEmit(savedFeedbackEvent(isOffline(), savedResId, queuedResId))
    }

    /** A write that is certainly queued (the send just failed offline): offline kind. */
    fun showQueued(@StringRes messageResId: Int) {
        _events.tryEmit(MutationFeedbackEvent(messageResId, kind = FeedbackKind.Offline))
    }

    /** Something came back from the trash. */
    fun showRestored(@StringRes messageResId: Int) {
        _events.tryEmit(MutationFeedbackEvent(messageResId, kind = FeedbackKind.Restored))
    }

    /** Something was deleted for good (trash icon, destructive tint). */
    fun showPurged(@StringRes messageResId: Int) {
        _events.tryEmit(MutationFeedbackEvent(messageResId, kind = FeedbackKind.Purged))
    }

    /** Something was removed without a trash to undo it from (a passkey, a linked login). */
    fun showRemoved(@StringRes messageResId: Int, vararg formatArgs: Any) {
        _events.tryEmit(
            MutationFeedbackEvent(messageResId, formatArgs.toList(), kind = FeedbackKind.Deleted)
        )
    }

    /** "Moved to the trash" with an Undo action that restores [item] (see `TrashUndoViewModel`). */
    fun showTrashed(@StringRes messageResId: Int, item: TrashRef, vararg formatArgs: Any) {
        _events.tryEmit(
            MutationFeedbackEvent(
                messageResId,
                formatArgs.toList(),
                TrashUndo(listOf(item)),
                FeedbackKind.Deleted,
            )
        )
    }

    /**
     * "Moved to the trash" for steps and photos an editor save removed: Undo restores exactly
     * [items]. Nothing is shown when the save removed nothing.
     */
    fun showTrashedBySave(@StringRes messageResId: Int, items: List<TrashRef>) {
        if (items.isEmpty()) return
        _events.tryEmit(
            MutationFeedbackEvent(
                messageResId,
                emptyList(),
                TrashUndo(items, true),
                FeedbackKind.Deleted,
            )
        )
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
    onUndo: (TrashUndo) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnUndo by rememberUpdatedState(onUndo)
    val undoLabel = stringResource(R.string.trash_undo)
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
                            actionLabel = undoLabel,
                        )
                    if (result == SnackbarResult.ActionPerformed) currentOnUndo(undo)
                }
            }
        }
    }
}

private fun Context.mutationFeedbackKind(event: MutationFeedbackEvent): FeedbackKind =
    event.kind ?: defaultFeedbackKind(resources.getResourceEntryName(event.messageResId))

/**
 * The kind of a message that names none: `error_*` entries are failures, `*_offline` ones only ever
 * report "you're offline" and everything else is a confirmation. Never the name for "queued":
 * whether a write waits for the network is decided by the connectivity when it is reported
 * ([savedFeedbackEvent]).
 */
internal fun defaultFeedbackKind(resourceEntryName: String): FeedbackKind =
    when {
        resourceEntryName.startsWith("error_") -> FeedbackKind.Error
        resourceEntryName.endsWith("_offline") -> FeedbackKind.Offline
        else -> FeedbackKind.Success
    }

/** The confirmation of a stored write: offline wording and kind only when it truly waits. */
internal fun savedFeedbackEvent(
    offline: Boolean,
    @StringRes savedResId: Int,
    @StringRes queuedResId: Int,
): MutationFeedbackEvent =
    if (offline) MutationFeedbackEvent(queuedResId, kind = FeedbackKind.Offline)
    else MutationFeedbackEvent(savedResId, kind = FeedbackKind.Success)

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
