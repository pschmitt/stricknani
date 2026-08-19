package blue.anika.wolle.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

/**
 * Persistent Home feedback for the background sync/write-queue (user-requested, 2026-08-18, "like
 * in syncwich and nyetbox") - it never gates or replaces Room content, just reports on it. Simpler
 * than syncwich's full `SyncStatus` state machine (no live syncing/stale-threshold tracking) since
 * that needs deeper WorkManager state observation this pass didn't scope in; covers the states this
 * app's data actually exposes today: refreshing, queued-but-unsynced local edits (SNA-8), a replay
 * failure, or a plain last-synced timestamp.
 */
@Composable
fun HomeSyncStatusCard(
    isRefreshing: Boolean,
    lastSyncedMillis: Long?,
    pendingChangesCount: Int,
    hasSyncFailures: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isRefreshing ->
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                hasSyncFailures ->
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                pendingChangesCount > 0 ->
                    Icon(
                        Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                else ->
                    Icon(
                        Icons.Filled.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    syncStatusHeadline(isRefreshing, pendingChangesCount, hasSyncFailures),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    syncStatusDetails(
                        isRefreshing,
                        lastSyncedMillis,
                        pendingChangesCount,
                        hasSyncFailures,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun syncStatusHeadline(
    isRefreshing: Boolean,
    pendingChangesCount: Int,
    hasSyncFailures: Boolean,
): String =
    when {
        isRefreshing -> stringResource(R.string.sync_status_syncing)
        hasSyncFailures -> stringResource(R.string.sync_status_issue)
        pendingChangesCount > 0 -> stringResource(R.string.sync_status_changes_pending)
        else -> stringResource(R.string.sync_status_synced)
    }

@Composable
internal fun syncStatusDetails(
    isRefreshing: Boolean,
    lastSyncedMillis: Long?,
    pendingChangesCount: Int,
    hasSyncFailures: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): String =
    when {
        isRefreshing -> stringResource(R.string.sync_status_pulling_latest)
        hasSyncFailures ->
            pluralStringResource(
                R.plurals.sync_status_changes_failed,
                pendingChangesCount,
                pendingChangesCount,
            )
        pendingChangesCount > 0 ->
            pluralStringResource(
                R.plurals.sync_status_changes_waiting,
                pendingChangesCount,
                pendingChangesCount,
            )
        else -> formatRelativeSyncTime(lastSyncedMillis, nowMillis)
    }

@Composable
internal fun formatRelativeSyncTime(lastSyncAt: Long?, nowMillis: Long): String {
    if (lastSyncAt == null) return stringResource(R.string.sync_status_not_synced_yet)
    val deltaMillis = (nowMillis - lastSyncAt).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMillis)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMillis)
    return when {
        minutes < 1 -> stringResource(R.string.sync_status_last_synced_just_now)
        minutes < 60 -> stringResource(R.string.sync_status_last_synced_minutes, minutes)
        hours < 24 -> stringResource(R.string.sync_status_last_synced_hours, hours)
        days < 7 -> stringResource(R.string.sync_status_last_synced_days, days)
        else ->
            stringResource(
                R.string.sync_status_last_synced_date,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(lastSyncAt)),
            )
    }
}
