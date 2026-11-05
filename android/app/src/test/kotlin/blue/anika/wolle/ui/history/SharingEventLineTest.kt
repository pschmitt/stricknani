package blue.anika.wolle.ui.history

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditSubjectDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharingEventLineTest {
    @Test
    fun `sharing actions name the person and role, every other action has no line`() {
        val subject = AuditSubjectDto(id = 12, name = "Ben", role = "editor")

        assertEquals(
            SharingEventLine(R.string.history_shared, "Ben", "editor"),
            sharingEventLine("shared", subject),
        )
        assertEquals(
            R.string.history_share_role_changed,
            sharingEventLine("share_role_changed", subject)?.textRes,
        )
        assertEquals(
            R.string.history_share_revoked,
            sharingEventLine("share_revoked", subject)?.textRes,
        )
        assertEquals(R.string.history_share_left, sharingEventLine("share_left", subject)?.textRes)
        assertEquals(
            R.string.history_ownership_transferred,
            sharingEventLine("ownership_transferred", subject.copy(role = null))?.textRes,
        )
        assertNull(sharingEventLine("update", subject))
    }

    @Test
    fun `a deleted account has no name`() {
        val line = sharingEventLine("share_left", AuditSubjectDto(id = null, name = null))

        assertNull(line?.name)
    }

    @Test
    fun `the event decodes with and without a subject`() {
        val json = Json { ignoreUnknownKeys = true }
        val with =
            json.decodeFromString<AuditEventDto>(
                """{"id":1,"entity_type":"project","entity_id":4,"action":"shared",
                   "created_at":"2026-01-01T00:00:00Z",
                   "subject":{"id":12,"name":"Ben","role":"viewer"}}"""
            )
        val without =
            json.decodeFromString<AuditEventDto>(
                """{"id":2,"entity_type":"project","entity_id":4,"action":"update",
                   "created_at":"2026-01-01T00:00:00Z"}"""
            )

        assertEquals("Ben", with.subject?.name)
        assertEquals("viewer", with.subject?.role)
        assertNull(without.subject)
    }
}
