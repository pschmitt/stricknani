package blue.anika.wolle.ui.admin

import blue.anika.wolle.R
import blue.anika.wolle.data.backups.BackupEntryDto
import blue.anika.wolle.data.backups.BackupKind
import blue.anika.wolle.data.backups.BackupListDto
import blue.anika.wolle.data.backups.PendingRestoreDto
import blue.anika.wolle.data.backups.RestoreOutcomeDto
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPresentationTest {
    private fun entry(name: String, kind: String, createdAt: String) =
        BackupEntryDto(
            name = name,
            kind = kind,
            createdAt = createdAt,
            readable = true,
            restorable = true,
        )

    private fun outcome(
        status: String = "succeeded",
        preRestore: String = "pre-restore-1.sqlite3",
    ) =
        RestoreOutcomeDto(
            status = status,
            backup = "nightly-1.sqlite3",
            preRestore = preRestore,
            finishedAt = "2026-11-12T10:00:00Z",
            requestedByEmail = "a@example.invalid",
        )

    private val backups =
        listOf(
            entry("pre-restore-1.sqlite3", "pre_restore", "2026-11-12T09:59:00Z"),
            entry("manual-x.sqlite3", "manual", "2026-11-11T09:59:00Z"),
        )

    @Test
    fun `every kind has its own tone and icon`() {
        val styles = BackupKind.entries.map { it.style() }
        assertEquals(5, styles.map { it.tone }.toSet().size)
        assertEquals(5, styles.map { it.icon }.toSet().size)
    }

    @Test
    fun `kinds map to the web tones and labels`() {
        assertEquals(BackupTone.Nightly, BackupKind.NIGHTLY.style().tone)
        assertEquals(BackupTone.Update, BackupKind.PREDEPLOY.style().tone)
        assertEquals(BackupTone.Migration, BackupKind.PRE_MIGRATE.style().tone)
        assertEquals(BackupTone.Restore, BackupKind.PRE_RESTORE.style().tone)
        assertEquals(BackupTone.Manual, BackupKind.MANUAL.style().tone)
        assertEquals(R.string.backups_kind_predeploy, BackupKind.PREDEPLOY.style().label)
        assertEquals(BackupKind.MANUAL, BackupKind.fromWire("something_new"))
    }

    @Test
    fun `undo is offered for the safety copy of the last successful restore`() {
        val list = BackupListDto(available = true, lastRestore = outcome())
        assertEquals("pre-restore-1.sqlite3", undoCandidate(list, backups)?.name)
    }

    @Test
    fun `a stray pre-restore file without a restore record offers nothing`() {
        val list =
            BackupListDto(
                available = true,
                latestPreRestore = "pre-restore-1.sqlite3",
                lastRestore = null,
            )
        assertNull(undoCandidate(list, backups))
    }

    @Test
    fun `failed or pending restores and missing files offer nothing`() {
        assertNull(undoCandidate(BackupListDto(lastRestore = outcome(status = "failed")), backups))
        assertNull(
            undoCandidate(
                BackupListDto(
                    lastRestore = outcome(),
                    pendingRestore = PendingRestoreDto("x", "y", "2026-11-12T11:00:00Z"),
                ),
                backups,
            )
        )
        assertNull(
            undoCandidate(
                BackupListDto(lastRestore = outcome(preRestore = "pre-restore-2.sqlite3")),
                backups,
            )
        )
        assertNull(
            undoCandidate(
                BackupListDto(lastRestore = outcome(preRestore = "manual-x.sqlite3")),
                backups,
            )
        )
    }

    @Test
    fun `backups group by local day with today and yesterday labels`() {
        val zone = ZoneId.of("UTC")
        val rows =
            listOf(
                entry("a", "nightly", "2026-11-12T10:00:00Z"),
                entry("b", "manual", "2026-11-12T01:00:00Z"),
                entry("c", "predeploy", "2026-11-11T20:00:00Z"),
                entry("d", "predeploy", "2026-11-01T20:00:00Z"),
                entry("e", "manual", "not a time"),
            )
        val groups = groupBackupsByDay(rows, LocalDate.of(2026, 11, 12), zone)
        assertEquals(
            listOf(
                DayLabel.Today,
                DayLabel.Yesterday,
                DayLabel.On(LocalDate.of(2026, 11, 1)),
                DayLabel.Unknown,
            ),
            groups.map { it.label },
        )
        assertEquals(listOf("a", "b"), groups[0].entries.map { it.name })
        assertNotEquals(groups[0].label, groups[1].label)
    }
}
