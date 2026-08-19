package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
