package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.AttachmentDto
import blue.anika.wolle.data.api.dto.CategoryDto
import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectListItemDto
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnListItemDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import blue.anika.wolle.data.util.DateTimeUtils
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses sample JSON shaped like the Rust v2 serializers (`src/api_v2/{projects,yarns,
 * categories,media}.rs`, `docs/web-v2-contract.md`) and checks the v2 -> v1-domain mappers.
 */
class V2DtoTest {
    // The same settings as `NetworkModule.provideJson`.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val projectJson =
        """
        {"data": {
          "id": 42, "name": "Wool socks", "category": "Socks", "yarn": "Blue wool",
          "needles": "4 mm", "stitch_sample": null,
          "description": "**warm**", "description_html": "<p><strong>warm</strong></p>\n",
          "notes": null, "notes_html": null,
          "other_materials": null, "other_materials_html": null,
          "tags": ["socks", "winter gift"], "link": null, "link_archive": null,
          "is_ai_enhanced": false, "is_favorite": true,
          "created_at": "2026-11-01T12:00:00.000000Z",
          "updated_at": "2026-11-01T12:30:00.000000Z",
          "yarn_ids": [7],
          "steps": [{"id": 81, "title": "Cast on", "description": "*64 stitches*",
                     "description_html": "<p><em>64 stitches</em></p>\n", "step_number": 1}],
          "images": [{"id": 17, "url": "/media/projects/42/example.png",
                      "thumbnail_url": "/media/thumbnails/projects/42/thumb_example.jpg",
                      "alt_text": "Sleeve detail", "image_type": "photo",
                      "is_title_image": true, "is_stitch_sample": false, "step_id": 81}],
          "attachments": [{"id": 3, "filename": "a1b2.pdf", "original_filename": "pattern.pdf",
                           "content_type": "application/pdf", "size_bytes": 12345,
                           "url": "/media/projects/42/a1b2.pdf"}]
        }}
        """
            .trimIndent()

    private val yarnJson =
        """
        {"data": {
          "id": 7, "name": "Merino", "description": "soft", "description_html": "<p>soft</p>\n",
          "brand": "Acme", "colorway": "Blue", "dye_lot": null, "fiber_content": "100% wool",
          "weight_category": "DK", "recommended_needles": "4 mm", "weight_grams": 100,
          "length_meters": 250, "notes": null, "notes_html": null, "link": null,
          "link_archive": null, "is_ai_enhanced": false, "is_favorite": false,
          "created_at": "2026-11-01T12:00:00.000000Z", "updated_at": "2026-11-01T12:00:00.000000Z",
          "project_ids": [42],
          "photos": [{"id": 5, "url": "/media/yarns/7/y.jpg",
                      "thumbnail_url": "/media/thumbnails/yarns/7/thumb_y.jpg",
                      "alt_text": "", "is_primary": true}]
        }}
        """
            .trimIndent()

    @Test
    fun projectEnvelopeParsesAndMapsToV1Domain() {
        val dto = json.decodeFromString<V2Envelope<ProjectV2Dto>>(projectJson).data
        assertEquals("<p><strong>warm</strong></p>\n", dto.descriptionHtml)
        assertNull(dto.notesHtml)

        val v1: ProjectDto = dto.toV1()
        assertEquals(42, v1.id)
        assertEquals("Wool socks", v1.name)
        assertEquals(listOf("socks", "winter gift"), v1.tags)
        assertTrue(v1.isFavorite)
        assertEquals("2026-11-01T12:30:00.000000Z", v1.updatedAt)
        assertEquals(listOf(7), v1.yarnIds)
        assertEquals(1, v1.steps.size)
        assertEquals("Cast on", v1.steps[0].title)
        assertEquals("*64 stitches*", v1.steps[0].description)
        assertEquals(1, v1.steps[0].stepNumber)
        assertEquals(81, v1.images.single().stepId)
        assertEquals("pdf", v1.attachments.single().filename.substringAfterLast('.'))
    }

    @Test
    fun projectMapsToTheSameRoomRowAsV1() {
        val dto = json.decodeFromString<V2Envelope<ProjectV2Dto>>(projectJson).data
        val entity = dto.toRoomEntity(json)
        assertEquals(42, entity.id)
        assertEquals("Socks", entity.category)
        assertEquals("""["socks","winter gift"]""", entity.tagsJson)
        assertTrue(entity.isFavorite)
        assertEquals(
            DateTimeUtils.parseToEpochMillis("2026-11-01T12:30:00.000000Z"),
            entity.updatedAt,
        )
        assertEquals("/media/thumbnails/projects/42/thumb_example.jpg", entity.previewUrl)
        // `detailJson` must be exactly the v1 `ProjectDto` JSON so existing readers keep working.
        val roundTrip = json.decodeFromString<ProjectDto>(entity.detailJson)
        assertEquals(dto.toV1(), roundTrip)
        assertFalse(entity.detailJson.contains("_html"))
    }

    @Test
    fun yarnEnvelopeParsesAndMapsToV1Domain() {
        val dto = json.decodeFromString<V2Envelope<YarnV2Dto>>(yarnJson).data
        val v1: YarnDto = dto.toV1()
        assertEquals("Merino", v1.name)
        assertEquals(100, v1.weightGrams)
        assertNull(v1.dyeLot)
        assertEquals(listOf(42), v1.projectIds)
        assertTrue(v1.photos.single().isPrimary)

        val entity = dto.toRoomEntity(json)
        assertEquals("DK", entity.weightCategory)
        assertEquals("/media/thumbnails/yarns/7/thumb_y.jpg", entity.previewUrl)
        assertEquals(v1, json.decodeFromString<YarnDto>(entity.detailJson))
    }

    @Test
    fun projectListEnvelopeMapsToV1Page() {
        val body =
            """
            {"data": [{"id": 42, "name": "Wool socks", "category": null, "tags": ["socks"],
                       "is_favorite": false, "updated_at": "2026-11-01T12:30:00.000000Z",
                       "preview_url": "/media/thumbnails/projects/42/thumb_example.jpg"}],
             "meta": {"page": 1, "per_page": 50, "has_more": false}}
            """
                .trimIndent()
        val page = json.decodeFromString<V2ListEnvelope<ProjectListItemDto>>(body).toV1Page()
        assertEquals(1, page.page)
        assertEquals(50, page.perPage)
        assertFalse(page.hasMore)
        assertEquals("Wool socks", page.items.single().name)
        assertNull(page.items.single().category)
    }

    @Test
    fun yarnListEnvelopeMapsToV1Page() {
        val body =
            """
            {"data": [{"id": 7, "name": "Merino", "brand": null, "colorway": null,
                       "weight_category": "DK", "is_favorite": true,
                       "updated_at": "2026-11-01T12:00:00.000000Z", "preview_url": null}],
             "meta": {"page": 2, "per_page": 50, "has_more": true}}
            """
                .trimIndent()
        val page = json.decodeFromString<V2ListEnvelope<YarnListItemDto>>(body).toV1Page()
        assertEquals(2, page.page)
        assertTrue(page.hasMore)
        assertNull(page.items.single().previewUrl)
    }

    @Test
    fun categoryAndMediaEnvelopesParse() {
        val categories =
            json.decodeFromString<V2Envelope<List<CategoryDto>>>(
                """{"data": [{"id": 1, "name": "Socks"}, {"id": 2, "name": "Hats"}]}"""
            )
        assertEquals(listOf("Socks", "Hats"), categories.data.map { it.name })

        val image =
            json
                .decodeFromString<V2Envelope<ImageDto>>(
                    """
                    {"data": {"id": 17, "url": "/media/projects/42/example.png",
                     "thumbnail_url": "/media/thumbnails/projects/42/thumb_example.jpg",
                     "alt_text": "Sleeve detail", "image_type": "photo",
                     "is_title_image": false, "is_stitch_sample": false, "step_id": null}}
                    """
                )
                .data
        assertEquals(17, image.id)
        assertNull(image.stepId)

        val attachment =
            json
                .decodeFromString<V2Envelope<AttachmentDto>>(
                    """
                    {"data": {"id": 3, "filename": "a1b2.pdf", "original_filename": "pattern.pdf",
                     "content_type": "application/pdf", "size_bytes": 12345,
                     "url": "/media/projects/42/a1b2.pdf"}}
                    """
                )
                .data
        assertEquals(12_345L, attachment.sizeBytes)

        val photo =
            json
                .decodeFromString<V2Envelope<YarnPhotoDto>>(
                    """
                    {"data": {"id": 5, "url": "/media/yarns/7/y.jpg",
                     "thumbnail_url": "/media/thumbnails/yarns/7/thumb_y.jpg",
                     "alt_text": "", "is_primary": false}}
                    """
                )
                .data
        assertFalse(photo.isPrimary)
    }

    @Test
    fun conflictBodyYieldsTheCurrentServerEntity() {
        val body =
            """
            {"error": {"code": "conflict", "message": "Project changed since it was loaded",
             "current": ${projectJson.removePrefix("{\"data\": ").removeSuffix("}")}}}
            """
                .trimIndent()
        val current = parseV2Conflict<ProjectV2Dto>(json, body)
        assertNotNull(current)
        assertEquals(42, current!!.id)
        assertEquals("Wool socks", current.name)
    }

    @Test
    fun conflictBodyParsingToleratesGarbage() {
        assertNull(parseV2Conflict<ProjectV2Dto>(json, ""))
        assertNull(parseV2Conflict<ProjectV2Dto>(json, "<html>bad gateway</html>"))
        assertNull(
            parseV2Conflict<ProjectV2Dto>(
                json,
                """{"error": {"code": "session_expired", "message": "Sign in to continue"}}""",
            )
        )
    }

    @Test
    fun validationErrorKeepsFieldMessages() {
        val body =
            """{"error": {"code": "validation_error", "message": "Project fields are invalid",
               "fields": {"name": "Invalid value"}}}"""
        val error =
            json.decodeFromString<blue.anika.wolle.data.api.dto.ApiErrorEnvelopeDto>(body).error
        assertEquals("validation_error", error?.code)
        assertEquals("Invalid value", error?.fields?.get("name"))
    }

    @Test
    fun writeRequestsEncodeLikeV1() {
        val request =
            blue.anika.wolle.data.api.dto.ProjectWriteRequest(
                name = "Hat",
                tags = listOf("a b"),
                expectedUpdatedAt = "2026-11-01T12:30:00.000000Z",
            )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"expected_updated_at\""))
        assertTrue(encoded.contains("\"a b\""))
    }
}
