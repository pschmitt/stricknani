package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectStatus
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.repository.toEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The project `status` on the wire (v2), in the cached detail and in queued writes. */
class ProjectStatusDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun projectJson(status: String?) =
        """
        {"id": 42, "name": "Hat", "is_ai_enhanced": false, "is_favorite": false,
         ${status?.let { "\"status\": \"$it\"," } ?: ""}
         "created_at": "2026-11-01T12:00:00.000000Z", "updated_at": "2026-11-01T12:30:00.000000Z"}
        """
            .trimIndent()

    @Test
    fun `a v2 project reads its status and a payload without one is in progress`() {
        assertEquals(
            ProjectStatus.DONE,
            json.decodeFromString<ProjectV2Dto>(projectJson("done")).status,
        )
        assertEquals(
            ProjectStatus.IN_PROGRESS,
            json.decodeFromString<ProjectV2Dto>(projectJson(null)).status,
        )
    }

    @Test
    fun `the status survives the v1 mapping and the Room row, and unknown values mean in progress`() {
        val done = json.decodeFromString<ProjectV2Dto>(projectJson("done")).toV1()
        assertEquals(ProjectStatus.DONE, done.status)
        assertEquals(ProjectStatus.DONE, done.toEntity(json).status)
        assertEquals(
            ProjectStatus.DONE,
            json.decodeFromString<ProjectDto>(json.encodeToString(done)).status,
        )
        val odd = json.decodeFromString<ProjectV2Dto>(projectJson("paused")).toV1()
        assertEquals(ProjectStatus.IN_PROGRESS, odd.status)
        assertEquals(ProjectStatus.IN_PROGRESS, ProjectStatus.normalize(null))
    }

    @Test
    fun `a write without a status leaves it out so an older queued edit keeps the status`() {
        assertFalse(json.encodeToString(ProjectWriteRequest(name = "Hat")).contains("status"))
        val withStatus = ProjectWriteRequest(name = "Hat", status = ProjectStatus.DONE)
        assertTrue(json.encodeToString(withStatus).contains("\"status\":\"done\""))
    }

    @Test
    fun `the status request body is the one field the server expects`() {
        assertEquals(
            "{\"status\":\"done\"}",
            json.encodeToString(ProjectStatusRequest(ProjectStatus.DONE)),
        )
    }
}
