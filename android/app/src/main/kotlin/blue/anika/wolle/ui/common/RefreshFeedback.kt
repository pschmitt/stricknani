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
    // True on a screen with its own status card: offline/error outcomes are shown there, so no
    // snackbar says it a second time, and the state stays until the next refresh replaces it.
    problemsShownInline: Boolean = false,
) {
    val messageResource = refreshFeedbackResource(state)
    val message = messageResource?.let { stringResource(it) }

    LaunchedEffect(state) {
        if (state is RefreshState.Idle || state is RefreshState.Refreshing) return@LaunchedEffect
        if (problemsShownInline && (state is RefreshState.Offline || state is RefreshState.Error))
            return@LaunchedEffect

        message?.let {
            val kind =
                when (state) {
                    RefreshState.Offline -> FeedbackKind.Offline
                    RefreshState.Error -> FeedbackKind.Error
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
