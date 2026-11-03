package blue.anika.wolle.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R

/** Returns the resource for feedback worth showing, or null for an automatic no-change refresh. */
internal fun refreshFeedbackResource(state: RefreshState): Int? =
    when (state) {
        is RefreshState.Finished ->
            when {
                state.changed -> R.string.refresh_success
                state.trigger == RefreshTrigger.UserInitiated -> R.string.refresh_no_changes
                else -> null
            }
        RefreshState.Offline -> R.string.refresh_offline
        RefreshState.ServerUnreachable -> R.string.refresh_server_unreachable
        RefreshState.Error -> R.string.refresh_error
        RefreshState.Idle,
        is RefreshState.Refreshing -> null
    }

/** Shows the completed outcome; the pull-to-refresh indicator itself represents [Refreshing]. */
@Composable
fun RefreshFeedbackEffect(
    state: RefreshState,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
    // True on a screen with its own status card (Home): every outcome is shown there, so no
    // snackbar repeats it. Offline/error states stay until the next refresh replaces them; other
    // outcomes are consumed silently.
    problemsShownInline: Boolean = false,
) {
    val messageResource = refreshFeedbackResource(state)
    val message = messageResource?.let { stringResource(it) }

    LaunchedEffect(state) {
        if (state is RefreshState.Idle || state is RefreshState.Refreshing) return@LaunchedEffect
        if (problemsShownInline) {
            if (state.isProblem()) return@LaunchedEffect
            onConsumed()
            return@LaunchedEffect
        }

        message?.let {
            val kind =
                when (state) {
                    RefreshState.Offline -> FeedbackKind.Offline
                    RefreshState.Error,
                    RefreshState.ServerUnreachable -> FeedbackKind.Error
                    is RefreshState.Finished ->
                        if (state.changed) FeedbackKind.Success else FeedbackKind.Info
                    else -> FeedbackKind.Info
                }
            snackbarHostState.showFeedback(it, kind)
        }
        // Automatic no-change refreshes have no useful message, but still need to be consumed so
        // the state does not remain stuck at Finished until the next explicit gesture.
        onConsumed()
    }
}

/** A refresh that could not check for news: offline, server unreachable or failed. */
fun RefreshState.isProblem(): Boolean =
    this is RefreshState.Offline ||
        this is RefreshState.ServerUnreachable ||
        this is RefreshState.Error
