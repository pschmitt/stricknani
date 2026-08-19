package blue.anika.wolle.ui.common

import androidx.compose.material3.SnackbarDuration
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
        RefreshState.Refreshing -> null
    }

/** Shows the completed outcome; the pull-to-refresh indicator itself represents [Refreshing]. */
@Composable
fun RefreshFeedbackEffect(
    state: RefreshState,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val messageResource = refreshFeedbackResource(state)
    val message = messageResource?.let { stringResource(it) }

    LaunchedEffect(state) {
        if (state is RefreshState.Idle || state is RefreshState.Refreshing) return@LaunchedEffect

        message?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) }
        // Automatic no-change refreshes have no useful message, but still need to be consumed so
        // the state does not remain stuck at Finished until the next explicit gesture.
        onConsumed()
    }
}
