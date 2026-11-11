package blue.anika.wolle.ui.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import blue.anika.wolle.ui.admin.AdminFailureState
import blue.anika.wolle.ui.common.TrashedList
import blue.anika.wolle.ui.common.YarnLoader

/**
 * The body of a project or yarn list while its Trash filter is on: the loader, the offline /
 * forbidden / error panel with a retry (the Trash screen's own), or [ready] once the trashed items
 * arrived. [onLeave] is the panel's way out: it turns the Trash filter off again.
 */
@Composable
fun <T> TrashedListBody(
    state: TrashedList<T>,
    onRetry: () -> Unit,
    onLeave: () -> Unit,
    ready: @Composable (List<T>) -> Unit,
) {
    when (state) {
        TrashedList.Idle,
        TrashedList.Loading ->
            Box(
                Modifier.fillMaxSize().testTag("trashed-list-loading"),
                contentAlignment = Alignment.Center,
            ) {
                YarnLoader()
            }
        is TrashedList.Failed ->
            AdminFailureState(
                failure = state.failure.asAdminFailure(),
                onRetry = onRetry,
                onBack = onLeave,
                modifier = Modifier.testTag("trashed-list-failed"),
            )
        is TrashedList.Ready -> ready(state.items)
    }
}
