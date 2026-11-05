package blue.anika.wolle.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRevisionTest {
    @Test
    fun blankAndUnknownHaveNoRevision() {
        assertNull(BuildRevision.parse(null))
        assertNull(BuildRevision.parse("  "))
        assertNull(BuildRevision.parse("unknown"))
        assertNull(BuildRevision.parse("-dirty"))
    }

    @Test
    fun shortShaIsShownAsIs() {
        val revision = BuildRevision.parse("9a7bfc70")!!
        assertEquals("9a7bfc70", revision.display)
        assertFalse(revision.dirty)
    }

    @Test
    fun dirtyKeepsTheSuffixInTheDisplayButNotTheSha() {
        val revision = BuildRevision.parse("9a7bfc70-dirty")!!
        assertEquals("9a7bfc70-dirty", revision.display)
        assertTrue(revision.dirty)
        assertEquals("9a7bfc70", revision.ref)
    }

    @Test
    fun longShaIsCutToEightCharacters() {
        val revision = BuildRevision.parse("9a7bfc70abcdef0123456789abcdef0123456789")!!
        assertEquals("9a7bfc70", revision.display)
        assertEquals("9a7bfc70abcdef0123456789abcdef0123456789", revision.ref)
    }

    @Test
    fun describeOutputYieldsTheCommit() {
        val revision = BuildRevision.parse("v1.4.0-12-g9a7bfc70abcd-dirty")!!
        assertEquals("9a7bfc70-dirty", revision.display)
        assertEquals("9a7bfc70abcd", revision.ref)
    }

    @Test
    fun exactTagIsShownAsTheTag() {
        val revision = BuildRevision.parse("v1.4.0")!!
        assertEquals("v1.4.0", revision.display)
        assertEquals("https://github.com/pschmitt/stricknani/commit/v1.4.0", revision.commitUrl)
    }

    @Test
    fun commitUrlNeverContainsDirty() {
        assertEquals(
            "https://github.com/pschmitt/stricknani/commit/9a7bfc70",
            BuildRevision.parse("9a7bfc70-dirty")!!.commitUrl,
        )
        assertEquals(
            "https://github.com/pschmitt/stricknani/commit/9a7bfc70abcd",
            BuildRevision.parse("v1.4.0-12-g9a7bfc70abcd")!!.commitUrl,
        )
    }
}
