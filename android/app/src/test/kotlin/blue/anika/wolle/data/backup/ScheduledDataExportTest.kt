package blue.anika.wolle.data.backup

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledDataExportTest {
    @Test
    fun `the name uses the utc date`() {
        assertEquals(
            "stricknani-export-2026-11-07.tar.gz",
            scheduledDataExportName(Instant.parse("2026-11-07T23:59:59Z")),
        )
        assertEquals(
            "stricknani-export-2026-11-08.tar.gz",
            scheduledDataExportName(Instant.parse("2026-11-08T00:00:00Z")),
        )
    }

    @Test
    fun `only the app's own names match`() {
        assertTrue(isDataExportName("stricknani-export-2026-11-07.tar.gz"))
        assertFalse(isDataExportName("stricknani-export-2026-11-07.zip"))
        assertFalse(isDataExportName("stricknani-export-2026-11-07.tar.gz.part"))
        assertFalse(isDataExportName("my-stricknani-export-2026-11-07.tar.gz"))
        assertFalse(isDataExportName("stricknani-backup-2026-11-07.stricknanibackup"))
        assertFalse(isDataExportName("stricknani-export-latest.tar.gz"))
    }

    @Test
    fun `the newest three stay`() {
        val names =
            listOf(
                "stricknani-export-2026-11-01.tar.gz",
                "stricknani-export-2026-11-08.tar.gz",
                "stricknani-export-2026-10-25.tar.gz",
                "stricknani-export-2026-11-04.tar.gz",
                "stricknani-export-2026-10-01.tar.gz",
                "notes.txt",
                "stricknani-export-2026-11-04.zip",
                "stricknani-backup-2026-10-01.stricknanibackup",
            )
        assertEquals(
            listOf("stricknani-export-2026-10-25.tar.gz", "stricknani-export-2026-10-01.tar.gz"),
            dataExportsToDelete(names),
        )
    }

    @Test
    fun `nothing is deleted below the limit`() {
        assertEquals(
            emptyList<String>(),
            dataExportsToDelete(listOf("stricknani-export-2026-11-01.tar.gz")),
        )
        assertEquals(emptyList<String>(), dataExportsToDelete(emptyList()))
        assertEquals(
            listOf("stricknani-export-2026-11-01.tar.gz"),
            dataExportsToDelete(listOf("stricknani-export-2026-11-01.tar.gz"), keep = 0),
        )
    }

    @Test
    fun `the name gets age when encrypted`() {
        assertEquals(
            "stricknani-export-2026-11-08.tar.gz.age",
            scheduledDataExportName(Instant.parse("2026-11-08T10:00:00Z"), encrypted = true),
        )
    }

    @Test
    fun `both suffixes match and nothing else`() {
        assertTrue(isDataExportName("stricknani-export-2026-11-07.tar.gz.age"))
        assertFalse(isDataExportName("stricknani-export-2026-11-07.tar.gz.age.part"))
        assertFalse(isDataExportName("stricknani-export-2026-11-07.tar.age"))
    }

    @Test
    fun `pruning counts both suffixes together`() {
        val names =
            listOf(
                "stricknani-export-2026-11-01.tar.gz",
                "stricknani-export-2026-11-08.tar.gz.age",
                "stricknani-export-2026-10-25.tar.gz",
                "stricknani-export-2026-11-04.tar.gz.age",
                "stricknani-export-2026-11-06.tar.gz",
                "stricknani-export-2026-11-04.zip.age",
                "notes.txt.age",
            )
        assertEquals(
            listOf("stricknani-export-2026-11-01.tar.gz", "stricknani-export-2026-10-25.tar.gz"),
            dataExportsToDelete(names),
        )
    }

    @Test
    fun `the plan follows the password`() {
        assertEquals(ScheduledDataPlan.Plain, scheduledDataPlan(null))
        assertEquals(ScheduledDataPlan.Plain, scheduledDataPlan(""))
        assertEquals(ScheduledDataPlan.PasswordTooShort, scheduledDataPlan("short"))
        assertEquals(ScheduledDataPlan.PasswordTooShort, scheduledDataPlan("12345678901"))
        assertEquals(ScheduledDataPlan.Encrypted, scheduledDataPlan("123456789012"))
    }

    @Test
    fun `the length counts utf-8 bytes`() {
        // Six characters, twelve bytes.
        assertEquals(ScheduledDataPlan.Encrypted, scheduledDataPlan("üüüüüü"))
        assertEquals(ScheduledDataPlan.PasswordTooShort, scheduledDataPlan("üüüüü"))
    }
}
