package blue.anika.wolle.sync

import blue.anika.wolle.data.api.dto.ProjectDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers `parseConflictDetail` (SNA-33) - the pure JSON-envelope-unwrapping half of
 * [WriteReplayWorker]'s conflict handling. The rest (catching `HttpException`, calling the
 * repositories' adopt/drop methods) needs a live Retrofit/Room stack this module doesn't currently
 * mock for unit tests, so it's covered instead by the backend's own conflict tests
 * (`tests/test_api_v1.py`) plus manual on-device verification - see `android/TODO.md`'s SNA-33
 * note.
 */
class WriteReplayWorkerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parseConflictDetail decodes FastAPI's detail envelope`() {
        val body =
            """
            {"detail": {"id": 7, "name": "Edited elsewhere", "updated_at": "2026-08-19T00:00:00+00:00", "is_favorite": false, "created_at": "2026-08-19T00:00:00+00:00", "yarn_ids": [], "steps": [], "images": [], "attachments": [], "tags": [], "is_ai_enhanced": false}}
            """
                .trimIndent()

        val decoded = parseConflictDetail<ProjectDto>(json, body)

        assertEquals(7, decoded?.id)
        assertEquals("Edited elsewhere", decoded?.name)
    }

    @Test
    fun `parseConflictDetail returns null for a body with no detail key`() {
        assertNull(parseConflictDetail<ProjectDto>(json, """{"message": "not a conflict"}"""))
    }

    @Test
    fun `parseConflictDetail returns null for malformed JSON rather than throwing`() {
        assertNull(parseConflictDetail<ProjectDto>(json, "not json at all"))
    }
}
