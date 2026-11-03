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

/** v2 error envelope handling (SNA-87): `{error:{code, message, fields, current}}`. */
class WriteReplayWorkerV2ConflictTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val projectV2 =
        """
        {"id": 7, "name": "Edited elsewhere", "description": "x", "description_html": "<p>x</p>",
         "updated_at": "2026-11-03T10:00:00.000000Z", "created_at": "2026-11-01T10:00:00.000000Z",
         "is_favorite": false, "is_ai_enhanced": false, "tags": ["winter gift"],
         "yarn_ids": [], "steps": [], "images": [], "attachments": []}
        """
            .trimIndent()

    @Test
    fun `v2 conflict yields the current project mapped to the v1 domain`() {
        val body = """{"error":{"code":"conflict","message":"changed","current":$projectV2}}"""

        val decoded = parseProjectConflict(json, body)

        assertEquals(7, decoded?.id)
        assertEquals("Edited elsewhere", decoded?.name)
        assertEquals(listOf("winter gift"), decoded?.tags)
        assertEquals("2026-11-03T10:00:00.000000Z", decoded?.updatedAt)
    }

    @Test
    fun `v1 detail conflict still works through the dispatcher`() {
        val body = """{"detail": $projectV2}"""
        assertEquals(7, parseProjectConflict(json, body)?.id)
    }

    @Test
    fun `v2 yarn conflict yields the current yarn`() {
        val body =
            """
            {"error":{"code":"conflict","current":{"id": 9, "name": "Merino", "is_ai_enhanced": false,
             "is_favorite": true, "created_at": "2026-11-01T10:00:00.000000Z",
             "updated_at": "2026-11-03T10:00:00.000000Z", "project_ids": [], "photos": []}}}
            """
                .trimIndent()
        val decoded = parseYarnConflict(json, body)
        assertEquals(9, decoded?.id)
        assertEquals(true, decoded?.isFavorite)
    }

    @Test
    fun `errors without a current entity and garbage give null`() {
        assertNull(
            parseProjectConflict(
                json,
                """{"error":{"code":"validation_error","message":"bad","fields":{"name":"required"}}}""",
            )
        )
        assertNull(parseProjectConflict(json, """{"error":{"code":"not_found"}}"""))
        assertNull(parseProjectConflict(json, ""))
        assertNull(parseYarnConflict(json, "<html>502</html>"))
    }
}
