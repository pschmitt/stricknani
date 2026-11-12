package blue.anika.wolle.ui.history

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditDetailDto
import blue.anika.wolle.data.api.dto.AuditDetailEnvelopeDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditMediaDto
import blue.anika.wolle.data.api.dto.AuditPageDto
import blue.anika.wolle.data.api.dto.ChangeItemDto
import blue.anika.wolle.data.api.dto.ChangeSetDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPresentationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun text(field: String, before: String?, after: String?) =
        ChangeItemDto(
            field,
            buildJsonObject {
                put("type", "text")
                put("value", before)
            },
            buildJsonObject {
                put("type", "text")
                put("value", after)
            },
        )

    private fun count(field: String, before: Int, after: Int) =
        ChangeItemDto(
            field,
            buildJsonObject {
                put("type", "integer")
                put("value", before)
            },
            buildJsonObject {
                put("type", "integer")
                put("value", after)
            },
        )

    private fun set(vararg items: ChangeItemDto) = ChangeSetDto(items = items.toList())

    private fun event(id: Long, action: String = "updated", hasChanges: Boolean = true) =
        AuditEventDto(
            id = id,
            entityType = "project",
            entityId = 1,
            action = action,
            createdAt = "2026-05-01T10:00:00Z",
            hasChanges = hasChanges,
        )

    @Test
    fun `changed fields are named once each`() {
        val parts =
            summaryParts(
                set(
                    text("project.name", "a", "b"),
                    text("project.notes", "", "x"),
                    text("yarn.notes", "", "x"),
                )
            )
        assertEquals(
            listOf(
                SummaryPart.ChangedFields(
                    listOf(R.string.history_noun_name, R.string.history_noun_notes)
                )
            ),
            parts,
        )
    }

    @Test
    fun `counts become added and removed phrases`() {
        assertEquals(
            listOf(SummaryPart.CountChange(HistoryCountKind.Photo, 2)),
            summaryParts(set(count("project.image_count", 1, 3))),
        )
        assertEquals(
            listOf(SummaryPart.CountChange(HistoryCountKind.Photo, -1)),
            summaryParts(set(count("yarn.photo_count", 2, 1))),
        )
        assertEquals(
            listOf(SummaryPart.CountChange(HistoryCountKind.Step, -1)),
            summaryParts(set(count("project.step_count", 4, 3))),
        )
    }

    @Test
    fun `a step count change does not also name the steps field`() {
        val steps =
            ChangeItemDto(
                "project.steps",
                buildJsonObject {
                    put("type", "steps")
                    put("value", kotlinx.serialization.json.JsonArray(emptyList()))
                },
                buildJsonObject {
                    put("type", "steps")
                    put("value", kotlinx.serialization.json.JsonArray(emptyList()))
                },
            )
        val parts =
            summaryParts(
                set(text("project.notes", "", "x"), count("project.step_count", 0, 1), steps)
            )
        assertEquals(2, parts.size)
        assertEquals(
            SummaryPart.ChangedFields(listOf(R.string.history_noun_notes)),
            parts[0],
        )
        assertEquals(SummaryPart.CountChange(HistoryCountKind.Step, 1), parts[1])
    }

    @Test
    fun `nothing summarisable gives no parts`() {
        assertTrue(summaryParts(null).isEmpty())
        assertTrue(summaryParts(set()).isEmpty())
        assertTrue(summaryParts(set(count("project.image_count", 2, 2))).isEmpty())
    }

    @Test
    fun `lists are joined naturally`() {
        assertEquals("", joinNatural(emptyList(), "and"))
        assertEquals("name", joinNatural(listOf("name"), "and"))
        assertEquals("name and notes", joinNatural(listOf("name", "notes"), "and"))
        assertEquals("a, b und c", joinNatural(listOf("a", "b", "c"), "und"))
    }

    @Test
    fun `collapsed shows the newest three and show all reveals the rest`() {
        val events = (10L downTo 1L).map { event(it) }
        assertEquals(listOf(10L, 9L, 8L), visibleEvents(events, showAll = false).map { it.id })
        assertEquals(10, visibleEvents(events, showAll = true).size)
        assertTrue(canShowAll(events, hasMore = false))
        assertFalse(canShowAll(events.take(3), hasMore = false))
        assertTrue(canShowAll(events.take(2), hasMore = true))
    }

    @Test
    fun `merging a page drops events already shown`() {
        val merged = mergeEvents(listOf(event(5), event(4)), listOf(event(4), event(3)))
        assertEquals(listOf(5L, 4L, 3L), merged.map { it.id })
    }

    @Test
    fun `history page and detail bodies parse like the audit shapes`() {
        val page =
            json.decodeFromString<AuditPageDto>(
                """{"data":[{"id":7,"actor_email":null,"actor_avatar_url":null,
                "entity_type":"project","entity_id":1,"action":"updated",
                "created_at":"2026-05-01T10:00:00Z","has_changes":true}],
                "meta":{"page":1,"per_page":20,"has_more":true}}"""
            )
        assertEquals(7L, page.data.single().id)
        assertTrue(page.meta.hasMore)
        assertEquals(20, page.meta.perPage)

        val detail =
            json.decodeFromString<AuditDetailEnvelopeDto>(
                """{"data":{"event":{"id":7,"entity_type":"project","entity_id":1,
                "action":"updated","created_at":"2026-05-01T10:00:00Z","has_changes":false},
                "changes":null,"unavailable_reason":"not_recorded"}}"""
            )
        assertNull(detail.data.changes)
        assertEquals("not_recorded", detail.data.unavailableReason)
        assertEquals(JsonPrimitive("x").content, "x")
    }

    // --- History entry -> details sheet body ---------------------------------------------------

    private fun loaded(event: AuditEventDto, changes: ChangeSetDto?, reason: String? = null) =
        HistoryDetailState.Loaded(AuditDetailDto(event, changes, reason))

    @Test
    fun `an entry with nothing recorded says so without loading`() {
        val e = event(1, hasChanges = false)
        assertEquals(HistoryDetailBody.NoDetails, historyDetailBody(e, null))
        assertEquals(HistoryDetailBody.NoDetails, historyDetailBody(e, HistoryDetailState.Loading))
    }

    @Test
    fun `an upload shows its file whatever the detail state`() {
        val media = AuditMediaDto(kind = "image", id = 3, name = "front.png", isImage = true)
        val e = event(2, "image_uploaded", hasChanges = false).copy(media = media)
        assertEquals(HistoryDetailBody.Media(media), historyDetailBody(e, null))
        assertEquals(
            HistoryDetailBody.Media(media),
            historyDetailBody(e, HistoryDetailState.Failed),
        )
    }

    @Test
    fun `an entry with changes loads then shows its field diff`() {
        val e = event(3)
        assertEquals(HistoryDetailBody.Loading, historyDetailBody(e, null))
        assertEquals(HistoryDetailBody.Loading, historyDetailBody(e, HistoryDetailState.Loading))
        assertEquals(HistoryDetailBody.Failed, historyDetailBody(e, HistoryDetailState.Failed))
        val items = listOf(text("project.name", "a", "b"))
        assertEquals(
            HistoryDetailBody.Changes(items),
            historyDetailBody(e, loaded(e, set(*items.toTypedArray()))),
        )
    }

    @Test
    fun `a snapshot that was never recorded reads as no details`() {
        val e = event(5)
        assertEquals(
            HistoryDetailBody.NoDetails,
            historyDetailBody(e, loaded(e, null, "not_recorded")),
        )
    }

    @Test
    fun `an unavailable snapshot keeps its reason`() {
        val e = event(4)
        assertEquals(
            HistoryDetailBody.Unavailable("too_large"),
            historyDetailBody(e, loaded(e, null, "too_large")),
        )
    }
}
