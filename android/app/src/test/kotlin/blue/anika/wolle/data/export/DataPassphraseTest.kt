package blue.anika.wolle.data.export

import java.time.LocalDate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPassphraseTest {
    @Test
    fun `length counts UTF-8 bytes, not characters`() {
        assertEquals(11, passphraseBytes("abcdefghijk"))
        // 8 characters, 15 bytes: the ä takes two bytes and the yarn emoji four.
        assertEquals(15, passphraseBytes("Schäfchen 🧶"))
        assertEquals(PassphraseProblem.TooShort, passphraseProblem("abcdefghijk", "abcdefghijk"))
        assertEquals(PassphraseProblem.TooShort, passphraseProblem("", ""))
        // Four emoji are four characters but 16 bytes: long enough.
        assertNull(passphraseProblem("🧶🧶🧶🧶", "🧶🧶🧶🧶"))
        // Six two-byte characters are 12 bytes.
        assertNull(passphraseProblem("ääääää", "ääääää"))
        assertEquals(PassphraseProblem.TooShort, passphraseProblem("äääää", "äääää"))
    }

    @Test
    fun `the length has an upper bound of 1024 bytes`() {
        val max = "a".repeat(PASSPHRASE_MAX_BYTES)
        assertNull(passphraseProblem(max, max))
        assertTrue(passphraseFits(max))
        val over = max + "a"
        assertEquals(PassphraseProblem.TooLong, passphraseProblem(over, over))
        assertFalse(passphraseFits(over))
        // 513 two-byte characters are 1026 bytes.
        assertFalse(passphraseFits("ä".repeat(513)))
    }

    @Test
    fun `a different repetition is a mismatch`() {
        assertEquals(
            PassphraseProblem.Mismatch,
            passphraseProblem("correct horse battery", "correct horse batterY"),
        )
        assertEquals(PassphraseProblem.Mismatch, passphraseProblem("correct horse battery", ""))
        assertNull(passphraseProblem("correct horse battery", "correct horse battery"))
    }

    @Test
    fun `the header is base64url of the UTF-8 bytes without padding`() {
        assertEquals(
            "Y29ycmVjdCBob3JzZSBiYXR0ZXJ5",
            encodePassphraseHeader("correct horse battery"),
        )
        assertEquals("U2Now6RmY2hlbiDwn6e2", encodePassphraseHeader("Schäfchen 🧶"))
        // The url-safe alphabet: `-` and `_` instead of `+` and `/`, and no `=` padding.
        assertEquals("YX5iPz4-", encodePassphraseHeader("a~b?>>"))
        val padded = encodePassphraseHeader("ab")
        assertEquals("YWI", padded)
        assertFalse(padded.contains('='))
    }

    @Test
    fun `an encrypted file name ends in age`() {
        val date = LocalDate.of(2026, 11, 8)
        assertEquals("stricknani-export-2026-11-08.tar.gz", exportFileName(date))
        assertEquals(
            "stricknani-export-2026-11-08.tar.gz.age",
            exportFileName(date, ExportFormat.TarGz, encrypted = true),
        )
        assertEquals(
            "stricknani-export-2026-11-08.zip.age",
            exportFileName(date, ExportFormat.Zip, encrypted = true),
        )
    }

    @Test
    fun `an encrypted download is opaque bytes`() {
        assertEquals("application/octet-stream", ExportFormat.TarGz.mimeTypeFor(true))
        assertEquals("application/octet-stream", ExportFormat.Zip.mimeTypeFor(true))
        assertEquals("application/gzip", ExportFormat.TarGz.mimeTypeFor(false))
        assertEquals("application/zip", ExportFormat.Zip.mimeTypeFor(false))
    }

    @Test
    fun `the ticket request carries archive and passphrase as json`() {
        val body =
            Buffer().also { encryptedExportBody(ExportFormat.Zip, "Schäfchen 🧶").writeTo(it) }
        assertEquals(
            """{"archive":"zip","passphrase":"Schäfchen 🧶"}""",
            body.readUtf8(),
        )
        val tar = Buffer().also { encryptedExportBody(ExportFormat.TarGz, "p").writeTo(it) }
        assertEquals("""{"archive":"tar.gz","passphrase":"p"}""", tar.readUtf8())
        assertEquals(
            "application/json",
            encryptedExportBody(ExportFormat.TarGz, "p")
                .contentType()
                ?.toString()
                ?.substringBefore(';'),
        )
    }

    @Test
    fun `the ticket url is read from the answer`() {
        val url = "/api/v2/account/export/download/abc123"
        val body = """{"data":{"url":"$url","expires_at":"2026-11-08T01:05:00Z"}}"""
        assertEquals(url, parseExportTicketUrl(body))
        assertEquals(TicketResult.Ready(url), ticketResultFor(201, body))
        assertNull(parseExportTicketUrl("{}"))
        assertNull(parseExportTicketUrl("not json"))
        assertNull(parseExportTicketUrl("""{"data":{"url":""}}"""))
    }

    @Test
    fun `a refused or unreadable ticket is a failure`() {
        assertEquals(
            TicketResult.Refused(ExportFailure.Unauthorized),
            ticketResultFor(401, null),
        )
        assertEquals(
            TicketResult.Refused(ExportFailure.AlreadyRunning),
            ticketResultFor(429, null),
        )
        assertEquals(TicketResult.Refused(ExportFailure.Failed), ticketResultFor(400, null))
        assertEquals(TicketResult.Refused(ExportFailure.Failed), ticketResultFor(201, "{}"))
        assertEquals(TicketResult.Refused(ExportFailure.Failed), ticketResultFor(201, null))
    }

    @Test
    fun `the two 422 codes have their own restore failures`() {
        assertEquals(
            RestoreFailure.PassphraseRequired,
            restoreFailureFor(422, "passphrase_required"),
        )
        assertEquals(RestoreFailure.WrongPassphrase, restoreFailureFor(422, "wrong_passphrase"))
        // Other 422s stay what they were.
        assertEquals(
            RestoreFailure.UnsupportedVersion,
            restoreFailureFor(422, "unsupported_format_version"),
        )
        assertEquals(RestoreFailure.Failed, restoreFailureFor(422, null))
        assertEquals(
            RestoreFailure.PassphraseRequired,
            parseRestoreErrorCode("""{"error":{"code":"passphrase_required"}}""")?.let {
                restoreFailureFor(422, it)
            },
        )
    }

    @Test
    fun `a job status keeps the passphrase failures by name`() {
        assertEquals(
            RestoreJobStatus.Failed(RestoreFailure.WrongPassphrase),
            restoreJobStatus(
                androidx.work.WorkInfo.State.FAILED,
                0,
                0,
                RestoreFailure.WrongPassphrase.name,
                RestoreSummary(),
            ),
        )
    }

    @Test
    fun `the holder gives a passphrase out once`() {
        val holder = PassphraseHolder()
        val id = holder.put("correct horse battery")
        assertEquals("correct horse battery", holder.take(id))
        assertNull(holder.take(id))
        assertEquals(0, holder.size)
    }

    @Test
    fun `every passphrase gets its own unguessable id`() {
        val holder = PassphraseHolder()
        val first = holder.put("one one one one")
        val second = holder.put("two two two two")
        assertNotEquals(first, second)
        assertFalse(first.contains("one"))
        assertEquals("two two two two", holder.take(second))
        assertEquals("one one one one", holder.take(first))
    }

    @Test
    fun `an unknown id finds nothing`() {
        assertNull(PassphraseHolder().take("no-such-id"))
    }

    @Test
    fun `a discarded passphrase is gone`() {
        val holder = PassphraseHolder()
        val id = holder.put("correct horse battery")
        holder.discard(id)
        assertNull(holder.take(id))
    }

    @Test
    fun `an old passphrase expires and is dropped`() {
        var now = 1_000L
        val holder = PassphraseHolder(ttlMillis = 100, clock = { now })
        val stale = holder.put("correct horse battery")
        now += 101
        assertNull(holder.take(stale))
        val kept = holder.put("another long passphrase")
        now += 100
        assertEquals("another long passphrase", holder.take(kept))
        // Putting purges what expired without being read.
        holder.put("left behind passphrase")
        now += 101
        holder.put("fresh passphrase here")
        assertEquals(1, holder.size)
    }
}
