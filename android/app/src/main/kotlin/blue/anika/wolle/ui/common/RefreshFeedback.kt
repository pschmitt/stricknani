package blue.anika.wolle.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R

/** Shows the completed outcome; the pull-to-refresh indicator itself represents [Refreshing]. */
@Composable
fun RefreshFeedbackEffect(
    state: RefreshState,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val message =
        when (state) {
            is RefreshState.Finished ->
                stringResource(
                    if (state.changed) R.string.refresh_success else R.string.refresh_no_changes
                )
            RefreshState.Offline -> stringResource(R.string.refresh_offline)
            RefreshState.Error -> stringResource(R.string.refresh_error)
            RefreshState.Idle,
            RefreshState.Refreshing -> null
        }

    LaunchedEffect(state) {
        message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            onConsumed()
        }
    }
}
