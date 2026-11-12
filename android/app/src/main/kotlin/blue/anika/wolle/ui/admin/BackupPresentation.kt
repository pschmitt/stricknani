package blue.anika.wolle.ui.admin

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.graphics.vector.ImageVector
import blue.anika.wolle.R
import blue.anika.wolle.data.backups.BackupEntryDto
import blue.anika.wolle.data.backups.BackupKind
import blue.anika.wolle.data.backups.BackupListDto
import blue.anika.wolle.ui.common.PhosphorIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Pure presentation rules of the Backups screen, mirroring
 * `web/src/lib/features/admin/backups-present.ts`: one badge per kind (a hue and an icon), the
 * Undo-card condition and the day groups. Keep kinds, tones, icons and labels in step with the web.
 */

/** The hue of a kind badge; the colours are the web's `--tone-*` pairs (see `Color.kt`). */
enum class BackupTone {
    Nightly,
    Update,
    Migration,
    Restore,
    Manual,
}

data class BackupKindStyle(
    val tone: BackupTone,
    val icon: ImageVector,
    @StringRes val label: Int,
)

fun BackupKind.style(): BackupKindStyle =
    when (this) {
        BackupKind.NIGHTLY ->
            BackupKindStyle(BackupTone.Nightly, Icons.Filled.Bedtime, R.string.backups_kind_nightly)
        BackupKind.PREDEPLOY ->
            BackupKindStyle(
                BackupTone.Update,
                Icons.Filled.Upload,
                R.string.backups_kind_predeploy,
            )
        BackupKind.PRE_MIGRATE ->
            BackupKindStyle(
                BackupTone.Migration,
                Icons.Filled.Storage,
                R.string.backups_kind_pre_migrate,
            )
        BackupKind.PRE_RESTORE ->
            BackupKindStyle(
                BackupTone.Restore,
                Icons.Filled.Restore,
                R.string.backups_kind_pre_restore,
            )
        BackupKind.MANUAL ->
            BackupKindStyle(BackupTone.Manual, PhosphorIcons.Needle, R.string.backups_kind_manual)
    }

/**
 * The backup the "Undo the last restore" card offers: the safety copy that the last *successful*
 * restore wrote, while that file still exists. A stray `pre-restore-*` file, a failed restore (the
 * data never changed) or a restore still waiting for the restart do not count.
 */
fun undoCandidate(list: BackupListDto, backups: List<BackupEntryDto>): BackupEntryDto? {
    val last = list.lastRestore ?: return null
    if (!last.succeeded || last.preRestore.isEmpty() || list.pendingRestore != null) return null
    return backups.firstOrNull {
        it.name == last.preRestore && it.kindOrManual == BackupKind.PRE_RESTORE
    }
}

data class BackupDayGroup(val label: DayLabel, val entries: List<BackupEntryDto>)

/**
 * Groups backups (newest first) into calendar days in [zone]; an entry whose time does not parse
 * lands in an [DayLabel.Unknown] group.
 */
fun groupBackupsByDay(
    backups: List<BackupEntryDto>,
    today: LocalDate,
    zone: ZoneId,
): List<BackupDayGroup> {
    val groups = linkedMapOf<DayLabel, MutableList<BackupEntryDto>>()
    for (entry in backups) {
        val instant = runCatching { Instant.parse(entry.createdAt) }.getOrNull()
        groups.getOrPut(dayLabelFor(instant, today, zone)) { mutableListOf() } += entry
    }
    return groups.map { (label, entries) -> BackupDayGroup(label, entries) }
}
