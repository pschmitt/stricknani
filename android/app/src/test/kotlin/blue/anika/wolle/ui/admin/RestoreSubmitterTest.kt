package blue.anika.wolle.ui.admin

import blue.anika.wolle.R
import blue.anika.wolle.data.backups.BackupCountsDto
import blue.anika.wolle.data.backups.BackupEntryDto
import blue.anika.wolle.data.backups.RestoreEvent
import blue.anika.wolle.data.backups.RestorePreviewDto
import blue.anika.wolle.data.backups.RestoreSnapshotDto
import blue.anika.wolle.data.backups.RestoreState
import blue.anika.wolle.data.backups.reduceRestore
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.ui.common.FakeDangerousActionGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreSubmitterTest {
    private val backup =
        BackupEntryDto(
            name = "pre-restore-20260102-030000.sqlite3",
            kind = "pre_restore",
            createdAt = "2026-01-02T03:00:00Z",
            sizeBytes = 10,
            hasMedia = false,
            schemaVersion = 37,
            counts = BackupCountsDto(1, 1, 1, 0),
            readable = true,
            restorable = true,
        )
    private val preview =
        RestorePreviewDto(
            backup = backup,
            current = RestoreSnapshotDto(37, backup.counts),
            backupSnapshot = RestoreSnapshotDto(37, backup.counts),
            changesSinceBackup = 1,
            mediaOptions = listOf("none"),
            restartsServer = true,
        )
    private val proof = ReauthProof(ReauthAction.RESTORE_BACKUP, ReauthTarget.None, "token")
    private val guard = FakeDangerousActionGuard()

    private fun confirmed(typed: String): RestoreState =
        listOf(
                RestoreEvent.Open(backup),
                RestoreEvent.PreviewLoaded(preview),
                RestoreEvent.Continue(1_000L),
                RestoreEvent.ReauthConfirmed(1_000L, proof),
                RestoreEvent.Typed(typed),
            )
            .fold(RestoreState.Closed as RestoreState, ::reduceRestore)

    @Test
    fun `a refused unlock sends nothing and leaves the confirmation as it was`() {
        guard.unlocked = false
        var state = confirmed(backup.name)
        val sent = mutableListOf<RestoreState.Confirm>()

        RestoreSubmitter(guard).submit(state, { state = reduceRestore(state, it) }, { sent += it })

        assertEquals(listOf(R.string.unlock_reason_restore_database), guard.requests)
        assertTrue(sent.isEmpty())
        assertTrue(state is RestoreState.Confirm)
    }

    @Test
    fun `after the unlock the flow moves on and the request is sent once`() {
        var state = confirmed(backup.name)
        val sent = mutableListOf<RestoreState.Confirm>()

        RestoreSubmitter(guard).submit(state, { state = reduceRestore(state, it) }, { sent += it })

        assertEquals(1, sent.size)
        assertEquals(backup.name, sent.single().typed)
        assertTrue(state is RestoreState.Restoring)
    }

    @Test
    fun `without the exact name the unlock is not even asked for`() {
        var state = confirmed("pre-restore")
        val sent = mutableListOf<RestoreState.Confirm>()

        RestoreSubmitter(guard).submit(state, { state = reduceRestore(state, it) }, { sent += it })

        assertTrue(guard.requests.isEmpty())
        assertTrue(sent.isEmpty())
    }
}
