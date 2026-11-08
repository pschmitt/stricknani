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
}
