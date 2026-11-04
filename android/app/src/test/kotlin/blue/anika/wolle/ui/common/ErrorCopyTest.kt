package blue.anika.wolle.ui.common

import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.ui.common.mascot.MascotMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCopyTest {
    @Test
    fun `status codes pick the web copy and mood`() {
        assertEquals(ErrorKind.NotFound, errorKindForStatus(404))
        assertEquals(ErrorKind.Forbidden, errorKindForStatus(401))
        assertEquals(ErrorKind.Forbidden, errorKindForStatus(403))
        assertEquals(ErrorKind.BadGateway, errorKindForStatus(502))
        assertEquals(ErrorKind.Unavailable, errorKindForStatus(503))
        assertEquals(ErrorKind.Timeout, errorKindForStatus(504))
        assertEquals(ErrorKind.Server, errorKindForStatus(500))
        assertEquals(MascotMood.Confused, ErrorKind.NotFound.mood)
        assertEquals(MascotMood.Dizzy, ErrorKind.ProjectMissing.mood)
        assertEquals(MascotMood.Tired, ErrorKind.Forbidden.mood)
        assertEquals(MascotMood.Sleepy, ErrorKind.Timeout.mood)
        assertEquals(MascotMood.Sad, ErrorKind.Offline.mood)
    }

    @Test
    fun `no response means offline, a real status wins over offline`() {
        assertEquals(ErrorKind.Offline, errorKindForStatus(null))
        assertEquals(ErrorKind.Offline, errorKindForStatus(0))
        assertEquals(ErrorKind.Offline, errorKindForStatus(500, offline = true))
        assertEquals(ErrorKind.NotFound, errorKindForStatus(404, offline = true))
        assertEquals(ErrorKind.Server, errorKindForStatus(418))
    }

    @Test
    fun `refresh states map to offline, unreachable and error panels`() {
        assertEquals(ErrorKind.Offline, RefreshState.Offline.errorKind())
        assertEquals(ErrorKind.Unavailable, RefreshState.ServerUnreachable.errorKind())
        assertEquals(ErrorKind.Server, RefreshState.Error.errorKind())
        assertNull(RefreshState.Idle.errorKind())
        assertNull(RefreshState.Finished(changed = true).errorKind())
    }

    @Test
    fun `admin failures map to panels`() {
        assertEquals(ErrorKind.Offline, AdminFailure.Offline.errorKind())
        assertEquals(ErrorKind.Forbidden, AdminFailure.NotAllowed.errorKind())
        assertEquals(ErrorKind.NotFound, AdminFailure.NotFound.errorKind())
        assertEquals(ErrorKind.Server, AdminFailure.Other.errorKind())
    }

    @Test
    fun `only recoverable errors offer a retry`() {
        assertFalse(ErrorKind.NotFound.retryable)
        assertFalse(ErrorKind.Forbidden.retryable)
        assertFalse(ErrorKind.ProjectMissing.retryable)
        assertTrue(ErrorKind.Offline.retryable)
        assertTrue(ErrorKind.Unavailable.retryable)
        assertTrue(ErrorKind.Server.retryable)
    }

    @Test
    fun `every kind has copy and at least one joke`() {
        for (kind in ErrorKind.entries) assertTrue(kind.name, kind.quips.isNotEmpty())
    }

    @Test
    fun `the joke pick is stable per seed and matches the web hash`() {
        val quips = listOf("a", "b", "c", "d")
        assertEquals(pickQuip("project-7", quips), pickQuip("project-7", quips))
        // Web: hash = (hash * 31 + code) >>> 0, then hash % length. "ab" -> 97 * 31 + 98 = 3105.
        assertEquals("b", pickQuip("ab", quips))
        // Empty seed hashes to 0.
        assertEquals("a", pickQuip("", quips))
        assertNull(pickQuip("x", emptyList<String>()))
    }

    @Test
    fun `different seeds spread over the jokes`() {
        val quips = listOf("a", "b", "c", "d")
        val picked = (1..40).map { pickQuip("yarn-$it", quips) }.toSet()
        assertTrue(picked.size > 1)
    }

    @Test
    fun `the hash stays in 32 bits for long seeds`() {
        val seed = "a-very-long-seed-".repeat(20)
        val quips = listOf(1, 2, 3)
        assertTrue(pickQuip(seed, quips) in quips)
    }
}
