package blue.anika.wolle.data.api.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses sample JSON shaped like the Rust `AuditEvent`/`AuditDetail`/`AdminUser` structs. */
class AdminDtoTest {
    // The same settings as `NetworkModule.provideJson`.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val eventJson =
        """
        {"id": 41, "actor_email": "admin@example.org",
         "actor_avatar_url": "/api/v2/admin/users/3/avatar/thumb_a1.jpg",
         "legacy_actor_user_id": null, "entity_type": "project", "entity_id": 7,
         "action": "updated", "created_at": "2026-11-02T18:30:05",
         "resource": {"kind": "project", "id": 7, "name": "Winter hat", "name_source": "current",
                      "href": "/app/admin/resources/project/7",
                      "preview_url": "/api/v2/admin/resources/project/7/media/image/9/thumbnail",
                      "exists": true},
         "has_changes": true}
        """
            .trimIndent()

    @Test
    fun `audit page decodes events and paging meta`() {
        val page =
            json.decodeFromString<AuditPageDto>(
                """{"data": [$eventJson], "meta": {"page": 1, "per_page": 50, "has_more": true}}"""
            )
        val event = page.data.single()
        assertEquals(41L, event.id)
        assertEquals("admin@example.org", event.actorEmail)
        assertEquals("updated", event.action)
        assertEquals("Winter hat", event.resource.name)
        assertEquals("project", event.resource.kind)
        assertTrue(event.resource.exists)
        assertTrue(event.hasChanges)
        assertTrue(page.meta.hasMore)
        assertEquals(50, page.meta.perPage)
    }

    @Test
    fun `deleted resource with no actor decodes with nulls`() {
        val event =
            json.decodeFromString<AuditEventDto>(
                """
                {"id": 5, "actor_email": null, "actor_avatar_url": null,
                 "legacy_actor_user_id": 12, "entity_type": "yarn", "entity_id": 3,
                 "action": "deleted", "created_at": "2026-11-02T18:30:05+00:00",
                 "resource": {"kind": null, "id": 3, "name": null, "name_source": "unavailable",
                              "href": null, "preview_url": null, "exists": false},
                 "has_changes": false}
                """
                    .trimIndent()
            )
        assertNull(event.actorEmail)
        assertEquals(12L, event.legacyActorUserId)
        assertNull(event.resource.kind)
        assertFalse(event.resource.exists)
    }

    @Test
    fun `detail decodes every change value type`() {
        val detail =
            json
                .decodeFromString<AuditDetailEnvelopeDto>(
                    """
                    {"data": {"event": $eventJson, "unavailable_reason": null,
                      "changes": {"version": 1, "items": [
                        {"field": "project.name",
                         "before": {"type": "text", "value": "Hat"},
                         "after": {"type": "text", "value": "Winter hat"}},
                        {"field": "project.tags",
                         "before": {"type": "text_list", "value": ["a"]},
                         "after": {"type": "text_list", "value": ["a", "b"]}},
                        {"field": "project.step_count",
                         "before": {"type": "integer", "value": null},
                         "after": {"type": "integer", "value": 4}},
                        {"field": "project.is_ai_enhanced",
                         "before": {"type": "boolean", "value": false},
                         "after": {"type": "boolean", "value": true}},
                        {"field": "project.link",
                         "before": {"type": "link_changed", "value": false},
                         "after": {"type": "link_changed", "value": true}},
                        {"field": "project.steps",
                         "before": {"type": "steps", "value": []},
                         "after": {"type": "steps", "value": [
                            {"number": 1, "title": "Cast on", "description": null},
                            {"number": 2, "title": "Knit", "description": "Ribbing"}]}},
                        {"field": "project.yarns",
                         "before": {"type": "legacy_text", "value": "Merino"},
                         "after": {"type": "yarns", "value": ["Merino", "Silk"]}}
                      ]}}}
                    """
                        .trimIndent()
                )
                .data
        assertNull(detail.unavailableReason)
        val items = detail.changes!!.items
        assertEquals(7, items.size)
        assertEquals(ChangeValue.Text("Hat"), parseChangeValue(items[0].before))
        assertEquals(ChangeValue.TextList(listOf("a", "b")), parseChangeValue(items[1].after))
        assertEquals(ChangeValue.Integer(null), parseChangeValue(items[2].before))
        assertEquals(ChangeValue.Integer(4), parseChangeValue(items[2].after))
        assertEquals(ChangeValue.Flag(true), parseChangeValue(items[3].after))
        assertEquals(ChangeValue.LinkChanged(true), parseChangeValue(items[4].after))
        assertEquals(
            ChangeValue.Steps(
                listOf(ChangeStep(1, "Cast on", null), ChangeStep(2, "Knit", "Ribbing"))
            ),
            parseChangeValue(items[5].after),
        )
        assertEquals(ChangeValue.LegacyText("Merino"), parseChangeValue(items[6].before))
        assertEquals(ChangeValue.Yarns(listOf("Merino", "Silk")), parseChangeValue(items[6].after))
    }

    @Test
    fun `detail without changes carries the unavailable reason`() {
        val detail =
            json
                .decodeFromString<AuditDetailEnvelopeDto>(
                    """{"data": {"event": $eventJson, "changes": null,
                        "unavailable_reason": "too_large"}}"""
                )
                .data
        assertNull(detail.changes)
        assertEquals("too_large", detail.unavailableReason)
    }

    @Test
    fun `unknown or malformed change values become Unknown instead of failing`() {
        assertEquals(
            ChangeValue.Unknown,
            parseChangeValue(json.parseToJsonElement("""{"type": "hologram", "value": 1}""")),
        )
        assertEquals(
            ChangeValue.Unknown,
            parseChangeValue(json.parseToJsonElement("""{"type": "boolean"}""")),
        )
        assertEquals(ChangeValue.Unknown, parseChangeValue(json.parseToJsonElement("[]")))
    }

    @Test
    fun `users page decodes accounts`() {
        val page =
            json.decodeFromString<AdminUsersPageDto>(
                """
                {"data": [
                   {"id": 1, "email": "a@example.org", "active": true, "is_admin": true,
                    "avatar_url": null},
                   {"id": 2, "email": "b@example.org", "active": false, "is_admin": false,
                    "avatar_url": "/api/v2/admin/users/2/avatar/thumb_x.jpg"}],
                 "meta": {"page": 1, "per_page": 50, "has_more": false}}
                """
                    .trimIndent()
            )
        assertEquals(2, page.data.size)
        assertTrue(page.data[0].isAdmin)
        assertNull(page.data[0].avatarUrl)
        assertFalse(page.data[1].active)
        assertEquals("/api/v2/admin/users/2/avatar/thumb_x.jpg", page.data[1].avatarUrl)
    }

    @Test
    fun `requests use the snake case names the server expects`() {
        assertEquals(
            """{"email":"a@example.org","password":"pw","is_admin":true}""",
            json.encodeToString(CreateAdminUserRequest("a@example.org", "pw", true)),
        )
        assertEquals("""{"active":false}""", json.encodeToString(SetUserActiveRequest(false)))
        assertEquals(
            """{"new_password":"secret"}""",
            json.encodeToString(ResetPasswordRequest("secret")),
        )
    }

    @Test
    fun `reset response and error body decode`() {
        val reset =
            json.decodeFromString<ResetPasswordEnvelopeDto>(
                """{"data": {"id": 4, "session_revoked": true}}"""
            )
        assertEquals(4L, reset.data.id)
        val error =
            json.decodeFromString<ApiErrorEnvelopeDto>(
                """{"error": {"code": "email_in_use", "message": "x",
                    "fields": {"email": "x"}}}"""
            )
        assertEquals("email_in_use", error.error?.code)
    }

    @Test
    fun `upload event decodes its media and an event without media decodes to null`() {
        val withMedia =
            json.decodeFromString<AuditEventDto>(
                """
                {"id": 8, "actor_email": "a@example.org", "actor_avatar_url": null,
                 "legacy_actor_user_id": null, "entity_type": "project", "entity_id": 7,
                 "action": "uploaded", "created_at": "2026-11-02T18:30:05Z",
                 "resource": {"kind": "project", "id": 7, "name": "Hat", "name_source": "event",
                              "href": "/app/projects/7", "preview_url": null, "exists": true},
                 "has_changes": false,
                 "media": {"kind": "image", "id": 12, "name": "front.png", "exists": true,
                           "is_image": true, "content_type": "image/png", "size_bytes": 120,
                           "url": "/api/v2/admin/resources/project/7/media/image/12",
                           "thumbnail_url": "/api/v2/admin/resources/project/7/media/image/12/thumbnail"}}
                """
                    .trimIndent()
            )
        val media = withMedia.media!!
        assertEquals(12L, media.id)
        assertEquals("front.png", media.name)
        assertTrue(media.exists && media.isImage)
        assertEquals("/app/projects/7", withMedia.resource.href)
        assertNull(json.decodeFromString<AuditEventDto>(eventJson).media)
    }

    @Test
    fun `removed event with deleted media has no urls`() {
        val removed =
            json.decodeFromString<AuditEventDto>(
                """
                {"id": 9, "entity_type": "project", "entity_id": 7, "action": "removed",
                 "created_at": "2026-11-02T18:30:06Z",
                 "media": {"kind": "attachment", "id": 3, "name": "chart.pdf", "exists": false,
                           "is_image": false, "content_type": null, "size_bytes": null,
                           "url": null, "thumbnail_url": null}}
                """
                    .trimIndent()
            )
        val media = removed.media!!
        assertFalse(media.exists)
        assertNull(media.url)
        assertEquals("chart.pdf", media.name)
    }
}
