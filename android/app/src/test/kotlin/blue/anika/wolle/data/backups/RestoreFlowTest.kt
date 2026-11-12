package blue.anika.wolle.data.backups

import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors `web/src/lib/features/admin/restore-flow.test.ts`. */
class RestoreFlowTest {
    private val backup =
        BackupEntryDto(
            name = "stricknani-20260101-020000.tar.gz",
            kind = "nightly",
            createdAt = "2026-01-01T02:00:00Z",
            sizeBytes = 10,
            hasMedia = true,
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
            changesSinceBackup = 4,
            mediaOptions = listOf("none", "add_missing"),
            restartsServer = true,
        )
    private val accepted =
        RestoreAcceptedDto(backup.name, "pre-restore-20260102-030000.sqlite3", restarting = true)
    private val proof = ReauthProof(ReauthAction.RESTORE_BACKUP, ReauthTarget.None, "token")
    private val now = 1_000_000L

    private fun run(
        vararg events: RestoreEvent,
        from: RestoreState = RestoreState.Closed,
    ): RestoreState = events.fold(from, ::reduceRestore)

    private fun toConfirm(typed: String? = backup.name): RestoreState =
        run(
            RestoreEvent.Open(backup),
            RestoreEvent.PreviewLoaded(preview),
            RestoreEvent.Continue(now),
            RestoreEvent.ReauthConfirmed(now, proof),
            *listOfNotNull(typed?.let { RestoreEvent.Typed(it) }).toTypedArray(),
        )

    @Test
    fun `walks preview, re-authentication, confirmation and restore`() {
        var state = run(RestoreEvent.Open(backup))
        assertTrue(state is RestoreState.Loading)
        state = run(RestoreEvent.PreviewLoaded(preview), from = state)
        assertTrue(state is RestoreState.Preview)
        state = run(RestoreEvent.Continue(now), from = state)
        assertTrue(state is RestoreState.Reauth)
        state = run(RestoreEvent.ReauthConfirmed(now, proof), from = state)
        assertTrue(state is RestoreState.Confirm)
        assertFalse(state.canRestore())
        state = run(RestoreEvent.Typed(backup.name), from = state)
        assertTrue(state.canRestore())
        state = run(RestoreEvent.Submit, from = state)
        assertTrue(state is RestoreState.Restoring)
        assertTrue(state.isLocked())
        state = run(RestoreEvent.Accepted(accepted, now), from = state)
        assertTrue(state is RestoreState.Waiting)
        assertTrue(state.isLocked())
        state = run(RestoreEvent.ServerBack(RestartResult.SUCCEEDED), from = state)
        assertTrue(state is RestoreState.Succeeded)
        assertEquals(RestoreState.Closed, run(RestoreEvent.Close, from = state))
    }

    @Test
    fun `only restorable backups open`() {
        assertEquals(
            RestoreState.Closed,
            run(RestoreEvent.Open(backup.copy(restorable = false))),
        )
    }

    @Test
    fun `needs the exact name, never a near miss`() {
        assertTrue(confirmationMatches(backup.name, backup.name))
        assertFalse(confirmationMatches(backup.name.uppercase(), backup.name))
        assertFalse(confirmationMatches(" ${backup.name}", backup.name))
        var state = toConfirm("stricknani")
        state = run(RestoreEvent.Submit, from = state)
        assertTrue(state is RestoreState.Confirm)
        state = run(RestoreEvent.Typed(backup.name), from = state)
        assertTrue(state.canRestore())
    }

    @Test
    fun `skips the prompt while a recent confirmation is still valid`() {
        val fresh = ReauthGrant(now - 60_000, proof)
        val skipped =
            run(
                RestoreEvent.Open(backup, fresh),
                RestoreEvent.PreviewLoaded(preview),
                RestoreEvent.Continue(now),
            )
        assertTrue(skipped is RestoreState.Confirm)
        assertSame(proof, (skipped as RestoreState.Confirm).grant.proof)
        val stale = ReauthGrant(now - REAUTH_WINDOW_MS, proof)
        val asked =
            run(
                RestoreEvent.Open(backup, stale),
                RestoreEvent.PreviewLoaded(preview),
                RestoreEvent.Continue(now),
            )
        assertTrue(asked is RestoreState.Reauth)
        assertFalse(grantIsFresh(null, now))
        assertFalse(grantIsFresh(ReauthGrant(now + 5, proof), now))
    }

    @Test
    fun `returns to the preview when the prompt is cancelled and keeps the media choice`() {
        var state =
            run(
                RestoreEvent.Open(backup),
                RestoreEvent.PreviewLoaded(preview),
                RestoreEvent.SetMedia(MediaOption.ADD_MISSING),
                RestoreEvent.Continue(now),
                RestoreEvent.ReauthCancelled,
            )
        assertTrue(state is RestoreState.Preview)
        assertEquals(MediaOption.ADD_MISSING, (state as RestoreState.Preview).media)
        state = run(RestoreEvent.SetMedia(MediaOption.NONE), from = state)
        assertEquals(MediaOption.NONE, (state as RestoreState.Preview).media)
    }

    @Test
    fun `rejects a media option the preview does not offer`() {
        val state =
            run(
                RestoreEvent.Open(backup),
                RestoreEvent.PreviewLoaded(preview.copy(mediaOptions = listOf("none"))),
                RestoreEvent.SetMedia(MediaOption.ADD_MISSING),
            )
        assertEquals(MediaOption.NONE, (state as RestoreState.Preview).media)
    }

    @Test
    fun `asks again when the server says the confirmation expired`() {
        val state =
            run(
                RestoreEvent.Submit,
                RestoreEvent.Rejected(RestoreErrorKind.UNKNOWN, reauthRequired = true),
                from = toConfirm(),
            )
        assertTrue(state is RestoreState.Reauth)
        assertEquals(null, (state as RestoreState.Reauth).grant)
    }

    @Test
    fun `keeps what was typed when the server rejects the request`() {
        val state =
            run(
                RestoreEvent.Submit,
                RestoreEvent.Rejected(RestoreErrorKind.NEWER_SCHEMA),
                from = toConfirm(),
            )
                as RestoreState.Confirm
        assertEquals(backup.name, state.typed)
        assertEquals(RestoreErrorKind.NEWER_SCHEMA, state.error)
    }

    @Test
    fun `cannot be dismissed while restoring or waiting, and waits for a manual restart`() {
        val restoring = run(RestoreEvent.Submit, from = toConfirm())
        assertSame(restoring, run(RestoreEvent.Close, from = restoring))
        val manual =
            run(
                RestoreEvent.Accepted(accepted.copy(restarting = false), now),
                from = restoring,
            )
        assertTrue(manual is RestoreState.ManualRestart)
        assertEquals(RestoreState.Closed, run(RestoreEvent.Close, from = manual))
    }

    @Test
    fun `ends in failure or timeout from the restart poll`() {
        val waiting =
            run(RestoreEvent.Submit, RestoreEvent.Accepted(accepted, now), from = toConfirm())
        val failed = run(RestoreEvent.ServerBack(RestartResult.FAILED, "boom"), from = waiting)
        assertEquals("boom", (failed as RestoreState.Failed).error)
        assertTrue(
            run(RestoreEvent.ServerBack(RestartResult.TIMEOUT), from = waiting)
                is RestoreState.Timeout
        )
    }

    @Test
    fun `ignores events that do not fit the phase`() {
        assertEquals(RestoreState.Closed, run(RestoreEvent.Submit))
        assertEquals(RestoreState.Closed, run(RestoreEvent.ServerBack(RestartResult.SUCCEEDED)))
        assertEquals(RestoreState.Closed, run(RestoreEvent.Typed("x")))
    }

    @Test
    fun `a failed preview shows the error and can be closed`() {
        val state =
            run(RestoreEvent.Open(backup), RestoreEvent.PreviewFailed(RestoreErrorKind.OFFLINE))
        assertTrue(state is RestoreState.Error)
        assertEquals(RestoreState.Closed, run(RestoreEvent.Close, from = state))
    }
}
