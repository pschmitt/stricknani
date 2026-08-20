package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedUpdateRequestTest {
    private val json = Json

    @Test
    fun `project update for a server row carries the cached timestamp`() {
        val request = ProjectWriteRequest(name = "Updated")
        val existing = projectDetail(id = 7, updatedAt = "2026-08-19T10:00:00Z")

        val queued = request.withExpectedUpdatedAt(7, json.encodeToString(existing), json)

        assertTrue(json.encodeToString(queued).contains("expected_updated_at"))
        assertTrue(queued.expectedUpdatedAt == existing.updatedAt)
    }

    @Test
    fun `project update for a temporary row omits the client timestamp`() {
        val request = ProjectWriteRequest(name = "Updated")
        val existing = projectDetail(id = -1, updatedAt = "2026-08-19T10:00:00Z")

        val queued = request.withExpectedUpdatedAt(-1, json.encodeToString(existing), json)

        assertFalse(json.encodeToString(queued).contains("expected_updated_at"))
        assertNull(queued.expectedUpdatedAt)
    }

    @Test
    fun `yarn update for a server row carries the cached timestamp`() {
        val request = YarnWriteRequest(name = "Updated")
        val existing = yarnDetail(id = 7, updatedAt = "2026-08-19T10:00:00Z")

        val queued = request.withExpectedUpdatedAt(7, json.encodeToString(existing), json)

        assertTrue(json.encodeToString(queued).contains("expected_updated_at"))
        assertTrue(queued.expectedUpdatedAt == existing.updatedAt)
    }

    @Test
    fun `yarn update for a temporary row omits the client timestamp`() {
        val request = YarnWriteRequest(name = "Updated")
        val existing = yarnDetail(id = -1, updatedAt = "2026-08-19T10:00:00Z")

        val queued = request.withExpectedUpdatedAt(-1, json.encodeToString(existing), json)

        assertFalse(json.encodeToString(queued).contains("expected_updated_at"))
        assertNull(queued.expectedUpdatedAt)
    }

    @Test
    fun `coalesced project edits retain the first server timestamp`() {
        val first = ProjectWriteRequest(name = "First", expectedUpdatedAt = "server-v1")
        val second = ProjectWriteRequest(name = "Second", expectedUpdatedAt = "client-v2")

        val coalesced = second.coalesceExpectedUpdatedAt(json.encodeToString(first), json)

        assertEquals("Second", coalesced.name)
        assertEquals("server-v1", coalesced.expectedUpdatedAt)
    }

    @Test
    fun `coalesced yarn edits retain the first server timestamp`() {
        val first = YarnWriteRequest(name = "First", expectedUpdatedAt = "server-v1")
        val second = YarnWriteRequest(name = "Second", expectedUpdatedAt = "client-v2")

        val coalesced = second.coalesceExpectedUpdatedAt(json.encodeToString(first), json)

        assertEquals("Second", coalesced.name)
        assertEquals("server-v1", coalesced.expectedUpdatedAt)
    }

    @Test
    fun `coalescing malformed previous payload keeps the new timestamp`() {
        val request = ProjectWriteRequest(name = "Latest", expectedUpdatedAt = "server-v1")

        val coalesced = request.coalesceExpectedUpdatedAt("not-json", json)

        assertEquals("server-v1", coalesced.expectedUpdatedAt)
    }

    private fun projectDetail(id: Int, updatedAt: String) =
        ProjectDto(
            id = id,
            name = "Cardigan",
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

    private fun yarnDetail(id: Int, updatedAt: String) =
        YarnDto(
            id = id,
            name = "Merino",
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )
}
