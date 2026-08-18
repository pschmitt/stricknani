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
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

/**
 * Persistent Home feedback for the background sync/write-queue (user-requested, 2026-08-18,
 * "like in syncwich and nyetbox") - it never gates or replaces Room content, just reports on it.
 * Simpler than syncwich's full `SyncStatus` state machine (no live syncing/stale-threshold
 * tracking) since that needs deeper WorkManager state observation this pass didn't scope in;
 * covers the states this app's data actually exposes today: refreshing, queued-but-unsynced local
 * edits (SNA-8), a replay failure, or a plain last-synced timestamp.
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

internal fun syncStatusHeadline(
    isRefreshing: Boolean,
    pendingChangesCount: Int,
    hasSyncFailures: Boolean,
): String =
    when {
        isRefreshing -> "Syncing…"
        hasSyncFailures -> "Sync issue"
        pendingChangesCount > 0 -> "Changes pending"
        else -> "Synced"
    }

internal fun syncStatusDetails(
    isRefreshing: Boolean,
    lastSyncedMillis: Long?,
    pendingChangesCount: Int,
    hasSyncFailures: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): String =
    when {
        isRefreshing -> "Pulling the latest projects and yarns."
        hasSyncFailures ->
            "$pendingChangesCount change${if (pendingChangesCount == 1) "" else "s"} couldn't reach the server yet."
        pendingChangesCount > 0 ->
            "$pendingChangesCount change${if (pendingChangesCount == 1) "" else "s"} waiting to sync."
        else -> formatRelativeSyncTime(lastSyncedMillis, nowMillis)
    }

internal fun formatRelativeSyncTime(lastSyncAt: Long?, nowMillis: Long): String {
    if (lastSyncAt == null) return "Not synced yet"
    val deltaMillis = (nowMillis - lastSyncAt).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMillis)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMillis)
    return when {
        minutes < 1 -> "Last synced just now"
        minutes < 60 -> "Last synced ${minutes}m ago"
        hours < 24 -> "Last synced ${hours}h ago"
        days < 7 -> "Last synced ${days}d ago"
        else ->
            "Last synced on " +
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(lastSyncAt))
    }
}
