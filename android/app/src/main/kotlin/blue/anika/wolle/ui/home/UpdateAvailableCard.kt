package blue.anika.wolle.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateInstallState
import blue.anika.wolle.ui.update.UpdateFailureActions
import blue.anika.wolle.ui.update.UpdateInstallProgress
import blue.anika.wolle.ui.update.updateInstallStatusText

/**
 * Prominent Home card shown when GitHub Releases has a newer build. "Update" starts the in-app
 * download and install (release APKs from GitHub); builds that cannot install themselves (debug)
 * hand the APK URL to the browser instead. While an install runs the card shows its progress, and a
 * failure offers "Try again" and the browser download.
 */
@Composable
fun UpdateAvailableCard(
    update: AppUpdate,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    install: UpdateInstallState = UpdateInstallState.Idle,
    onTryAgain: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("home-update-card"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        stringResource(R.string.update_available_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        updateInstallStatusText(install)
                            ?: stringResource(R.string.update_available_message, update.version),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("home-update-status"),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                install.isBusy -> UpdateInstallProgress(install)
                install is UpdateInstallState.Failed ->
                    UpdateFailureActions(install.failure, onTryAgain, onOpenBrowser)
                else ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_available_later))
                        }
                        Button(onClick = onDownload) {
                            Text(stringResource(R.string.update_available_download))
                        }
                    }
            }
        }
    }
}
