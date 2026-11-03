package blue.anika.wolle.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `parses the documented feed`() {
        val page =
            json.decodeFromString<ActivityPageDto>(
                """
                {"data":[
                  {"id":412,"first_event_id":409,"created_at":"2026-11-03T10:09:12.000000Z",
                   "first_created_at":"2026-11-03T10:00:03.000000Z","action":"updated",
                   "entity_type":"project","entity_id":17,"entity_name":"Winter socks","exists":true,
                   "href":"/app/projects/17","thumbnail_url":"/media/thumbnails/projects/17/t.jpg",
                   "actor":{"name":"philipp","is_self":true,"avatar_url":"/media/u.jpg"},
                   "changed_fields":["project.name","project.needles"],"count":4,"future":1},
                  {"id":405,"first_event_id":405,"created_at":"2026-11-02T18:30:00.000000Z",
                   "first_created_at":"2026-11-02T18:30:00.000000Z","action":"deleted",
                   "entity_type":"yarn","entity_id":3,"entity_name":null,"exists":false,
                   "href":null,"thumbnail_url":null,
                   "actor":{"name":null,"is_self":false,"avatar_url":null},
                   "changed_fields":[],"count":1}
                ],"meta":{"limit":10,"has_more":true,"next_before":405}}
                """
            )
        val first = page.data[0]
        assertEquals(412L, first.id)
        assertEquals(409L, first.firstEventId)
        assertEquals(4, first.count)
        assertTrue(first.exists)
        assertTrue(first.actor.isSelf)
        assertEquals(listOf("project.name", "project.needles"), first.changedFields)
        assertEquals("/media/thumbnails/projects/17/t.jpg", first.thumbnailUrl)
        val deleted = page.data[1]
        assertFalse(deleted.exists)
        assertNull(deleted.href)
        assertNull(deleted.entityName)
        assertNull(deleted.actor.name)
        assertTrue(page.meta.hasMore)
        assertEquals(405L, page.meta.nextBefore)
    }

    @Test
    fun `missing optional keys fall back to defaults`() {
        val page =
            json.decodeFromString<ActivityPageDto>(
                """{"data":[{"id":1,"created_at":"2026-11-03T10:09:12Z","action":"created",
                "entity_type":"project","entity_id":2}],"meta":{"limit":10,"has_more":false}}"""
            )
        val item = page.data.single()
        assertEquals(1, item.count)
        assertEquals(1L, item.firstEventId)
        assertTrue(item.changedFields.isEmpty())
        assertNull(page.meta.nextBefore)
    }
}
