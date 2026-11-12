package blue.anika.wolle.data.backups

import blue.anika.wolle.data.api.v2.V2Envelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors `backups-api.test.ts` (parsing) and `backups-format.test.ts`. */
class BackupsApiTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val entry =
        """{"name":"stricknani-20260101-020000.tar.gz","kind":"nightly","created_at":"2026-01-01T02:00:00Z",
        "size_bytes":1234,"has_media":true,"schema_version":37,
        "counts":{"users":3,"projects":2,"yarns":1,"trashed":1},"readable":true,
        "newer_schema":false,"restorable":true,"error":null,"extra":"ignored"}"""

    @Test
    fun `parses the listing and tolerates unknown kinds`() {
        val body =
            """{"data":{"available":true,"server_schema_version":37,
            "backups":[$entry,{"name":"x.sqlite3","kind":"weird"}],
            "latest_pre_restore":"pre-restore-20260102-030000.sqlite3","pending_restore":null,
            "last_restore":{"status":"failed","backup":"x.sqlite3","pre_restore":"p","media":"none",
            "finished_at":"2026-01-03T00:00:00Z","requested_by_email":"a@example.invalid","error":"boom"},
            "data_epoch":"abc","auto_restart":true}}"""
        val list = json.decodeFromString<V2Envelope<BackupListDto>>(body).data
        assertEquals(2, list.backups.size)
        assertEquals(3L, list.backups[0].counts?.users)
        assertEquals(BackupKind.NIGHTLY, list.backups[0].kindOrManual)
        assertEquals(BackupKind.MANUAL, list.backups[1].kindOrManual)
        assertFalse(list.backups[1].restorable)
        assertEquals("pre-restore-20260102-030000.sqlite3", list.latestPreRestore)
        assertFalse(list.lastRestore!!.succeeded)
        assertEquals("boom", list.lastRestore?.error)
        assertTrue(list.autoRestart)
        assertEquals("abc", list.dataEpoch)
    }

    @Test
    fun `parses the preview and keeps only known media options`() {
        val body =
            """{"data":{"backup":$entry,"current":{"schema_version":37,"counts":{"users":2}},
            "backup_snapshot":{"schema_version":37,"counts":{"users":3}},"changes_since_backup":12,
            "media_options":["none","add_missing","bogus"],"restarts_server":true}}"""
        val preview = json.decodeFromString<V2Envelope<RestorePreviewDto>>(body).data
        assertEquals(listOf(MediaOption.NONE, MediaOption.ADD_MISSING), preview.options)
        assertEquals(12L, preview.changesSinceBackup)
        assertEquals(3L, preview.backupSnapshot.counts?.users)
        assertNull(preview.backupSnapshot.counts?.projects)
        val none =
            json
                .decodeFromString<V2Envelope<RestorePreviewDto>>(
                    """{"data":{"backup":$entry,"media_options":["bogus"]}}"""
                )
                .data
        assertEquals(listOf(MediaOption.NONE), none.options)
    }

    @Test
    fun `restore request carries the exact name and the media option`() {
        assertEquals(
            """{"confirmation":"a.sqlite3","media":"add_missing"}""",
            json.encodeToString(
                RestoreRequestDto.serializer(),
                RestoreRequestDto("a.sqlite3", "add_missing"),
            ),
        )
    }

    @Test
    fun `maps server error codes to restore errors`() {
        fun kind(status: Int, code: String?) = backupsExceptionFor(status, code)
        assertTrue(kind(403, "reauth_required").reauthRequired)
        assertTrue(kind(403, "admin_required").notAllowed)
        assertTrue(kind(401, "session_expired").notAllowed)
        assertEquals(RestoreErrorKind.NEWER_SCHEMA, kind(409, "backup_newer_schema").kind)
        assertEquals(RestoreErrorKind.UNREADABLE, kind(409, "backup_unreadable").kind)
        assertEquals(RestoreErrorKind.BUSY, kind(409, "restore_in_progress").kind)
        assertEquals(RestoreErrorKind.PRE_RESTORE_FAILED, kind(500, "pre_restore_failed").kind)
        assertEquals(RestoreErrorKind.STAGE_FAILED, kind(500, "stage_failed").kind)
        assertEquals(RestoreErrorKind.CONFIRMATION, kind(422, "confirmation_mismatch").kind)
        assertEquals(RestoreErrorKind.MEDIA, kind(422, "media_unavailable").kind)
        assertEquals(RestoreErrorKind.UNAVAILABLE, kind(503, "backups_unavailable").kind)
        assertEquals(RestoreErrorKind.UNKNOWN, kind(500, null).kind)
    }

    @Test
    fun `formats sizes`() {
        assertEquals("0 B", formatBackupBytes(0))
        assertEquals("1023 B", formatBackupBytes(1023))
        assertEquals("1.5 KB", formatBackupBytes(1536))
        assertEquals("5.0 MB", formatBackupBytes(5L * 1024 * 1024))
        assertEquals("250 MB", formatBackupBytes(250L * 1024 * 1024))
        assertEquals("3.0 GB", formatBackupBytes(3L * 1024 * 1024 * 1024))
        assertEquals("", formatBackupBytes(-1))
    }

    @Test
    fun `shows unknown counts as a dash and spots differing counts`() {
        assertEquals("–", formatBackupCount(null))
        assertEquals("0", formatBackupCount(0))
        val a = BackupCountsDto(1, 2, null, 0)
        val b = BackupCountsDto(1, 3, null, 0)
        assertFalse(countsDiffer(a, b, CountKey.USERS))
        assertTrue(countsDiffer(a, b, CountKey.PROJECTS))
        assertFalse(countsDiffer(a, null, CountKey.YARNS))
        assertTrue(countsDiffer(null, b, CountKey.PROJECTS))
    }

    @Test
    fun `sorts newest first`() {
        fun e(at: String, name: String) = BackupEntryDto(name = name, createdAt = at)
        val rows =
            listOf(
                    e("2026-01-01T00:00:00Z", "b"),
                    e("2026-01-02T00:00:00Z", "a"),
                    e("2026-01-01T00:00:00Z", "a"),
                )
                .sortedWith(backupOrder)
        assertEquals(listOf("a", "a", "b"), rows.map { it.name })
        assertEquals("2026-01-02T00:00:00Z", rows[0].createdAt)
    }

    @Test
    fun `formats the time in the given zone and falls back to the raw text`() {
        assertEquals(
            "Jan 1, 2026, 2:00 AM",
            formatBackupTime(
                    "2026-01-01T02:00:00Z",
                    java.util.Locale.US,
                    java.time.ZoneOffset.UTC,
                )
                .replace(' ', ' '),
        )
        assertEquals("nope", formatBackupTime("nope"))
    }
}
