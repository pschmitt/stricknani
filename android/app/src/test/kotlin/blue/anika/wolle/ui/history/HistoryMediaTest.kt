package blue.anika.wolle.ui.history

import blue.anika.wolle.data.api.dto.AuditDetailEnvelopeDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditPageDto
import blue.anika.wolle.data.api.dto.ChangeItemDto
import blue.anika.wolle.data.api.dto.HistoryMediaDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMediaTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun event(extra: String = "") =
        """{"id":1,"entity_type":"project","entity_id":7,"action":"updated",
            "created_at":"2026-11-02T18:30:05Z","has_changes":true$extra}"""

    private fun decode(extra: String = ""): AuditEventDto =
        json.decodeFromString(AuditEventDto.serializer(), event(extra))

    @Test
    fun `an event without the key decodes with no pictures`() {
        val e = decode()
        assertNull(e.media)
        assertTrue(e.mediaThumbs.isEmpty())
    }

    @Test
    fun `the label map decodes into thumbnails`() {
        val e =
            decode(
                ""","media_thumbnails":{"1.jpg":{"url":"/media/a/1.jpg","thumbnail_url":"/media/t/1.jpg"},
                    "2.jpg":{"url":"/media/a/2.jpg"}}"""
            )
        assertNull(e.media)
        assertEquals(HistoryMediaDto("/media/a/1.jpg", "/media/t/1.jpg"), e.mediaThumbs["1.jpg"])
        assertNull(e.mediaThumbs["2.jpg"]?.thumbnailUrl)
    }

    @Test
    fun `an upload event keeps media next to the label map`() {
        val e =
            decode(
                ""","media":{"kind":"image","id":3,"name":"x.jpg","exists":true,"is_image":true,
                    "url":"/m/x.jpg","thumbnail_url":"/t/x.jpg"},
                    "media_thumbnails":{"x.jpg":{"url":"/m/x.jpg","thumbnail_url":"/t/x.jpg"}}"""
            )
        assertEquals("x.jpg", e.media?.name)
        assertEquals("/t/x.jpg", e.mediaThumbs["x.jpg"]?.thumbnailUrl)
    }

    @Test
    fun `an upload event without the map keeps its media object`() {
        val e = decode(""","media":{"kind":"image","id":3,"name":"x.jpg"}""")
        assertEquals("x.jpg", e.media?.name)
        assertTrue(e.mediaThumbs.isEmpty())
    }

    @Test
    fun `pages and detail envelopes carry the map`() {
        val media = ""","media_thumbnails":{"a.jpg":{"url":"/a","thumbnail_url":"/ta"}}"""
        val page =
            json.decodeFromString(
                AuditPageDto.serializer(),
                """{"data":[${event(media)}],"meta":{"page":1,"per_page":20,"has_more":false}}""",
            )
        assertNotNull(page.data.single().mediaThumbs["a.jpg"])
        val detail =
            json.decodeFromString(
                AuditDetailEnvelopeDto.serializer(),
                """{"data":{"event":${event(media)}}}""",
            )
        assertNotNull(detail.data.event.mediaThumbs["a.jpg"])
    }

    private fun list(vararg labels: String) = buildJsonObject {
        put("type", "text_list")
        put("value", JsonArray(labels.map { JsonPrimitive(it) }))
    }

    private fun text(value: String?) = buildJsonObject {
        put("type", "text")
        put("value", value)
    }

    private fun thumbs(vararg labels: String) = labels.associateWith {
        HistoryMediaDto("/$it", "/t/$it")
    }

    @Test
    fun `moving one picture marks only that picture`() {
        val item = ChangeItemDto("project.image_order", list("a", "b", "c"), list("c", "a", "b"))
        val rows = mediaChangeRows(item, thumbs("a", "b", "c"))!!
        assertEquals(
            listOf(MediaMark.Kept, MediaMark.Kept, MediaMark.Moved),
            rows.before.map { it.mark },
        )
        assertEquals(
            listOf(MediaMark.Moved, MediaMark.Kept, MediaMark.Kept),
            rows.after.map { it.mark },
        )
    }

    @Test
    fun `added and removed pictures are marked on their side`() {
        val item = ChangeItemDto("yarn.photo_order", list("a", "b"), list("b", "c"))
        val rows = mediaChangeRows(item, thumbs("a", "b", "c"))!!
        assertEquals(listOf(MediaMark.Removed, MediaMark.Kept), rows.before.map { it.mark })
        assertEquals(listOf(MediaMark.Kept, MediaMark.Added), rows.after.map { it.mark })
    }

    @Test
    fun `a cover change is one removed and one added picture`() {
        val item = ChangeItemDto("project.cover_image", text("a.jpg"), text("b.jpg"))
        val rows = mediaChangeRows(item, thumbs("a.jpg", "b.jpg"))!!
        assertEquals(listOf(MediaThumb("a.jpg", MediaMark.Removed)), rows.before)
        assertEquals(listOf(MediaThumb("b.jpg", MediaMark.Added)), rows.after)
    }

    @Test
    fun `text rendering stays when no picture is known or the field is not a picture field`() {
        val order = ChangeItemDto("project.image_order", list("a"), list("b"))
        assertNull(mediaChangeRows(order, emptyMap()))
        assertNull(mediaChangeRows(order, thumbs("z")))
        val name = ChangeItemDto("project.name", text("a"), text("b"))
        assertNull(mediaChangeRows(name, thumbs("a", "b")))
    }

    @Test
    fun `an empty cover before is an empty row`() {
        val item = ChangeItemDto("yarn.primary_photo", text(null), text("b.jpg"))
        val rows = mediaChangeRows(item, thumbs("b.jpg"))!!
        assertTrue(rows.before.isEmpty())
        assertEquals(MediaMark.Added, rows.after.single().mark)
    }

    @Test
    fun `the viewer swipes through the distinct pictures of a change with a url`() {
        val item = ChangeItemDto("project.image_order", list("a", "b", "x"), list("b", "c", "a"))
        val media = thumbs("a", "b", "c") + ("x" to HistoryMediaDto(null, "/t/x"))
        val rows = mediaChangeRows(item, media)!!
        assertEquals(listOf("b", "c", "a"), mediaViewerLabels(rows, media))
    }

    @Test
    fun `removed pictures follow the after pictures in the viewer`() {
        val item = ChangeItemDto("project.cover_image", text("a.jpg"), text("b.jpg"))
        val media = thumbs("a.jpg", "b.jpg")
        assertEquals(
            listOf("b.jpg", "a.jpg"),
            mediaViewerLabels(mediaChangeRows(item, media)!!, media),
        )
    }
}
