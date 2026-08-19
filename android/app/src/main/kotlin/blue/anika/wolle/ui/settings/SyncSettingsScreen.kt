package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import blue.anika.wolle.ui.common.hasRetryableSyncIssues
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
                    icon = Icons.Filled.Sync,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_sync_last_synced_label))
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
                    Text(
                        stringResource(presentation.headlineResId) +
                            " — " +
                            stringResource(presentation.detailResId)
                    )
                },
            )
        }
        if (issues.hasRetryableSyncIssues()) {
            SettingsListItem(
                headlineContent = { Text(stringResource(R.string.sync_issue_retry_title)) },
                supportingContent = { Text(stringResource(R.string.sync_issue_retry_description)) },
                trailingContent = {
                    TextButton(onClick = onRetry) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.sync_issue_retry))
                    }
                },
            )
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
