package blue.anika.wolle.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * Shown below the greeting when the last refresh could not check for news, until a later refresh
 * succeeds. The message says whether the phone is offline or the sync failed, and Retry sits below
 * the text so the title keeps the full width on a narrow phone.
 */
@Composable
fun SyncProblemCard(problem: SyncProblem, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().testTag("home-sync-problem-card")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.CloudSlash,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (problem == SyncProblem.Offline) R.string.sync_status_offline
                        else R.string.sync_status_refresh_failed
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.sync_status_showing_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry, modifier = Modifier.testTag("home-sync-retry")) {
                    Text(stringResource(R.string.sync_issue_retry))
                }
            }
        }
    }
}
