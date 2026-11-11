package blue.anika.wolle.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `parses the documented list with a child item and ignores unknown keys`() {
        val list =
            json.decodeFromString<TrashListDto>(
                """
                {"data":[
                  {"kind":"project","id":7,"name":"Socks",
                   "owner":{"id":2,"email":"anika@example.com","name":"Anika","avatar_url":"/media/u.jpg"},
                   "deleted_at":"2026-11-01T10:00:00Z","purge_at":"2027-01-30T10:00:00Z",
                   "days_remaining":80,"future":true},
                  {"kind":"step","id":9,"name":"Step 2","owner":null,
                   "deleted_at":"2026-11-02T10:00:00Z","purge_at":"2027-01-31T10:00:00Z",
                   "days_remaining":0,
                   "parent":{"kind":"project","id":7,"name":"Socks"},"detail":"2. Heel"},
                  {"kind":"hologram","id":1,"name":null}
                ],"meta":{"scope":"all","retention_days":90}}
                """
            )

        assertEquals("all", list.meta.scope)
        assertEquals(90, list.meta.retentionDays)
        val project = list.data[0]
        assertEquals("project", project.kind)
        assertEquals("Anika", project.owner?.name)
        assertEquals(80, project.daysRemaining)
        assertNull(project.parent)
        assertNull(project.detail)
        val step = list.data[1]
        assertEquals("project", step.parent?.kind)
        assertEquals("Socks", step.parent?.name)
        assertEquals("2. Heel", step.detail)
        assertEquals(0, step.daysRemaining)
        // An unknown kind of a newer server still parses, with every optional field defaulted.
        assertEquals("hologram", list.data[2].kind)
        assertNull(list.data[2].deletedAt)
    }

    @Test
    fun `an empty body still parses`() {
        val list = json.decodeFromString<TrashListDto>("""{"data":[]}""")
        assertTrue(list.data.isEmpty())
        assertEquals(TRASH_RETENTION_DAYS, list.meta.retentionDays)
        assertEquals(TRASH_SCOPE_MINE, list.meta.scope)
    }

    @Test
    fun `audit events keep old payloads valid and read the new trash fields`() {
        val old =
            json.decodeFromString<AuditEventDto>(
                """
                {"id":1,"entity_type":"project","entity_id":3,"action":"deleted",
                 "created_at":"2026-11-01T10:00:00Z",
                 "resource":{"kind":"project","id":3,"name":"Socks","exists":false}}
                """
            )
        assertNull(old.resource.state)
        assertNull(old.resource.trash)
        assertFalse(old.system)

        val fresh =
            json.decodeFromString<AuditEventDto>(
                """
                {"id":2,"entity_type":"project","entity_id":3,"action":"purged",
                 "created_at":"2026-11-01T10:00:00Z","system":true,
                 "subject":{"id":2,"name":"Anika","role":null},
                 "resource":{"kind":"project","id":3,"name":"Socks","exists":false,
                   "state":"in_trash",
                   "trash":{"deleted_at":"2026-10-01T10:00:00Z","purge_at":"2026-12-30T10:00:00Z",
                            "days_remaining":12,"can_restore":true}}}
                """
            )
        assertTrue(fresh.system)
        assertEquals("Anika", fresh.subject?.name)
        assertEquals(RESOURCE_STATE_IN_TRASH, fresh.resource.state)
        assertEquals(12, fresh.resource.trash?.daysRemaining)
        assertTrue(fresh.resource.trash?.canRestore == true)
    }

    @Test
    fun `activity items read state and trash at the top level`() {
        val item =
            json.decodeFromString<ActivityItemDto>(
                """
                {"id":5,"created_at":"2026-11-03T10:09:12Z","action":"trashed",
                 "entity_type":"yarn","entity_id":3,"entity_name":"Merino","exists":false,
                 "actor":{"name":"philipp","is_self":true},
                 "state":"in_trash",
                 "trash":{"days_remaining":88,"can_restore":true}}
                """
            )
        assertEquals(RESOURCE_STATE_IN_TRASH, item.state)
        assertEquals(88, item.trash?.daysRemaining)
        assertFalse(item.system)

        val old =
            json.decodeFromString<ActivityItemDto>(
                """
                {"id":6,"created_at":"2026-11-03T10:09:12Z","action":"updated",
                 "entity_type":"yarn","entity_id":3,"exists":true,"actor":{"is_self":false}}
                """
            )
        assertNull(old.state)
        assertNull(old.trash)
    }
}
