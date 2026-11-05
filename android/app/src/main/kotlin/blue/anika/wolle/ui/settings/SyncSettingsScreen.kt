package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.SyncFailureReason
import blue.anika.wolle.ui.common.hasRetryableSyncIssues
import blue.anika.wolle.ui.common.syncFailureReason
import blue.anika.wolle.ui.common.syncIssuePresentation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val lastSyncedMillis by viewModel.lastSyncedMillis.collectAsStateWithLifecycle()
    val failedMutations by viewModel.failedMutations.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingSyncCount.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Sync.title()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.settings_sync_group_title),
                    icon = PhosphorIcons.CloudCheck,
                ) {
                    SyncNowAction(syncStatus, pendingCount, viewModel::retryFailedMutations)
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_sync_last_received))
                        },
                        supportingContent = { Text(formatLastSynced(lastSyncedMillis)) },
                    )
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_sync_policy_label))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_sync_policy_description))
                        },
                    )
                }
            }
            if (failedMutations.isNotEmpty()) {
                item {
                    SyncIssuesSettingsCard(
                        issues = failedMutations,
                        onRetry = viewModel::retryFailedMutations,
                        syncStatus = syncStatus,
                        onDismiss = viewModel::dismissSyncIssue,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncIssuesSettingsCard(
    issues: List<PendingMutationEntity>,
    onRetry: () -> Unit,
    syncStatus: SyncWorkStatus,
    onDismiss: (Long) -> Unit,
) {
    SettingsGroupCard(
        title = stringResource(R.string.sync_issue_settings_title),
        icon = Icons.Filled.ErrorOutline,
    ) {
        issues.forEach { issue ->
            val presentation = issue.syncIssuePresentation()
            SettingsListItem(
                leadingContent = {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                headlineContent = { Text(stringResource(presentation.operationLabelResId)) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(presentation.headlineResId) +
                                " — " +
                                stringResource(presentation.detailResId)
                        )
                        if (!issue.isConflict) {
                            val reason =
                                when (syncFailureReason(issue.lastErrorMessage)) {
                                    SyncFailureReason.AUTHENTICATION ->
                                        R.string.settings_sync_error_auth
                                    SyncFailureReason.SERVER -> R.string.settings_sync_error_server
                                    SyncFailureReason.REJECTED ->
                                        R.string.settings_sync_error_rejected
                                    SyncFailureReason.NETWORK ->
                                        R.string.settings_sync_error_network
                                    SyncFailureReason.UNKNOWN ->
                                        R.string.settings_sync_error_unknown
                                }
                            Text(stringResource(reason))
                        }
                    }
                },
                trailingContent =
                    if (issue.isConflict) {
                        {
                            TextButton(onClick = { onDismiss(issue.id) }) {
                                Text(stringResource(R.string.sync_issue_dismiss))
                            }
                        }
                    } else null,
            )
        }
        if (issues.hasRetryableSyncIssues()) {
            SettingsListItem(
                headlineContent = { Text(stringResource(R.string.sync_issue_retry_title)) },
                supportingContent = { Text(stringResource(R.string.sync_issue_retry_description)) },
            )
            Button(
                onClick = onRetry,
                enabled = syncStatus != SyncWorkStatus.RUNNING,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.sync_issue_retry))
            }
        }
    }
}

@Composable
private fun formatLastSynced(millis: Long?): String {
    if (millis == null) return stringResource(R.string.settings_sync_never)
    val formatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(millis))
}

@Composable
private fun SyncNowAction(status: SyncWorkStatus, pendingCount: Int, onSync: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_sync_pending_count, pendingCount))
        val statusText =
            when (status) {
                SyncWorkStatus.IDLE -> R.string.settings_sync_ready
                SyncWorkStatus.RUNNING -> R.string.settings_sync_running
                SyncWorkStatus.WAITING -> R.string.settings_sync_waiting
                SyncWorkStatus.RETRY_SCHEDULED -> R.string.settings_sync_retry_scheduled
                SyncWorkStatus.SUCCEEDED ->
                    if (pendingCount == 0) R.string.settings_sync_complete
                    else R.string.settings_sync_remaining
                SyncWorkStatus.FAILED -> R.string.settings_sync_failed
            }
        Text(stringResource(statusText), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onSync,
            enabled = status != SyncWorkStatus.RUNNING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (status == SyncWorkStatus.RUNNING) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                stringResource(R.string.settings_sync_now),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
