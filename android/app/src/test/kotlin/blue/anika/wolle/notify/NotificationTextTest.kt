package blue.anika.wolle.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test
    fun `details are joined with a middle dot and blanks are dropped`() {
        assertEquals(
            "stricknani-export-2026-11-08.tar.gz · 2.4 MB",
            joinDetails("stricknani-export-2026-11-08.tar.gz", "2.4 MB"),
        )
        assertEquals("name", joinDetails("name", null, " "))
        assertEquals("", joinDetails(null, ""))
    }

    @Test
    fun `elapsed time is split into whole hours and minutes`() {
        assertEquals(ElapsedParts(0, 0), elapsedParts(-5))
        assertEquals(ElapsedParts(0, 45), elapsedParts(45 * 60_000L + 59_000))
        assertEquals(ElapsedParts(1, 5), elapsedParts(65 * 60_000L))
        assertEquals(ElapsedParts(26, 0), elapsedParts(26 * 3_600_000L))
    }

    @Test
    fun `release versions and rolling builds are told apart`() {
        assertEquals(VersionKind.Release, versionKind("1.6.0"))
        assertEquals(VersionKind.Release, versionKind("v1.6.0"))
        assertEquals(VersionKind.Build, versionKind("a1b2c3d"))
        assertEquals("1.6.0", displayVersion("v1.6.0"))
        assertEquals("a1b2c3d", displayVersion(" a1b2c3d "))
    }

    @Test
    fun `release notes lose markdown noise and the changelog footer`() {
        val body =
            """
            ## What's Changed
            * **Sync:** richer [notifications](https://example.test/pr/1) by @someone in https://example.test/pr/2
            - Fix `timer` crash

            **Full Changelog**: https://example.test/compare/v1.5.0...v1.6.0
            """
                .trimIndent()

        assertEquals(
            "What's Changed\n• Sync: richer notifications by @someone in\n• Fix timer crash",
            releaseNotesSummary(body),
        )
    }

    @Test
    fun `empty or link-only release notes give nothing`() {
        assertNull(releaseNotesSummary(null))
        assertNull(releaseNotesSummary("  \n"))
        assertNull(releaseNotesSummary("**Full Changelog**: https://example.test/x"))
        assertNull(releaseNotesSummary("https://example.test/x"))
    }

    @Test
    fun `release notes are cut to a few lines and a length`() {
        val many = (1..20).joinToString("\n") { "- item $it" }
        assertEquals(3, releaseNotesSummary(many, maxLines = 3)!!.lines().size)

        val long = releaseNotesSummary("x".repeat(2_000), maxChars = 50)!!
        assertEquals(50, long.length)
        assertTrue(long.endsWith("…"))
    }
}
