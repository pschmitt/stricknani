package blue.anika.wolle.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.update.UpdateFailure
import blue.anika.wolle.data.update.UpdateInstallState
import blue.anika.wolle.data.update.messageRes

/**
 * Keeps the controller informed that a screen is showing the update (so the system's confirmation
 * opens right away instead of as a notification) and opens that confirmation when it is due.
 */
@Composable
fun UpdateInstallEffects(viewModel: UpdateInstallViewModel, state: UpdateInstallState) {
    val context = LocalContext.current
    DisposableEffect(viewModel) {
        viewModel.attachUi()
        onDispose { viewModel.detachUi() }
    }
    LaunchedEffect(state) {
        if (state == UpdateInstallState.AwaitingConfirmation) {
            viewModel.takeConfirmationIntent()?.let {
                try {
                    context.startActivity(it)
                } catch (_: ActivityNotFoundException) {
                    // Nothing can show the dialog; the notification path is not available either.
                }
            }
        }
    }
}

/** One line for a running or waiting install; null when there is nothing in progress. */
@Composable
fun updateInstallStatusText(state: UpdateInstallState): String? =
    when (state) {
        is UpdateInstallState.Downloading ->
            state.fraction?.let {
                stringResource(R.string.update_state_downloading_percent, (it * 100).toInt())
            } ?: stringResource(R.string.update_state_downloading)
        UpdateInstallState.Verifying -> stringResource(R.string.update_state_verifying)
        UpdateInstallState.Installing -> stringResource(R.string.update_state_installing)
        UpdateInstallState.AwaitingConfirmation -> stringResource(R.string.update_state_confirm)
        is UpdateInstallState.Failed -> stringResource(state.failure.messageRes())
        UpdateInstallState.Idle -> null
    }

@Composable
fun UpdateInstallProgress(state: UpdateInstallState, modifier: Modifier = Modifier) {
    val fraction = (state as? UpdateInstallState.Downloading)?.fraction
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}

/** "Try again" (or "Allow installs" when that is what is missing) and the browser fallback. */
@Composable
fun UpdateFailureActions(
    failure: UpdateFailure,
    onTryAgain: () -> Unit,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onOpenBrowser) {
            Text(stringResource(R.string.update_action_browser))
        }
        if (failure == UpdateFailure.PERMISSION_NEEDED) {
            Button(
                onClick = {
                    val intent =
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        onTryAgain()
                    }
                }
            ) {
                Text(stringResource(R.string.update_action_allow_installs))
            }
        }
        Button(onClick = onTryAgain) { Text(stringResource(R.string.update_action_try_again)) }
    }
}
