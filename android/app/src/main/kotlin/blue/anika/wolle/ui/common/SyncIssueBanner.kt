package blue.anika.wolle.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.PendingMutationEntity

/** A compact, actionable summary of outbox failures shown near Home's sync status. */
@Composable
fun SyncIssueBanner(
    issues: List<PendingMutationEntity>,
    onRetry: () -> Unit,
    onOpenDetails: () -> Unit,
    isRetrying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (issues.isEmpty()) return

    val firstIssue = issues.first()
    val presentation = firstIssue.syncIssuePresentation()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (firstIssue.isConflict) Icons.Filled.ErrorOutline else Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.sync_issue_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    pluralStringResource(
                        R.plurals.sync_issue_count,
                        issues.size,
                        issues.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(presentation.operationLabelResId) +
                        ": " +
                        stringResource(presentation.headlineResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row {
                    TextButton(onClick = onOpenDetails) {
                        Text(stringResource(R.string.sync_issue_view_details))
                    }
                    if (issues.hasRetryableSyncIssues()) {
                        TextButton(onClick = onRetry, enabled = !isRetrying) {
                            if (isRetrying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sync_issue_retry))
                        }
                    }
                }
            }
        }
    }
}
