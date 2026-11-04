package blue.anika.wolle.ui.home

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ActivityActorDto
import blue.anika.wolle.data.api.dto.ActivityItemDto
import blue.anika.wolle.ui.admin.DayLabel
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPresentationTest {
    private fun item(
        id: Long = 1,
        at: String = "2026-11-03T10:09:12.000000Z",
        entityId: Int = 17,
        action: String = "updated",
        type: String = "project",
        exists: Boolean = true,
        fields: List<String> = emptyList(),
        count: Int = 1,
        self: Boolean = true,
        name: String? = "Winter socks",
        thumb: String? = "/media/t.jpg",
        actorName: String? = "philipp",
    ) =
        ActivityItemDto(
            id = id,
            createdAt = at,
            firstCreatedAt = at,
            action = action,
            entityType = type,
            entityId = entityId,
            entityName = name,
            exists = exists,
            thumbnailUrl = thumb,
            actor = ActivityActorDto(name = actorName, isSelf = self),
            changedFields = fields,
            count = count,
        )

    @Test
    fun `actions map to verbs and unknown ones to changed`() {
        assertEquals(ActivityVerb.Created, activityVerb("created"))
        assertEquals(ActivityVerb.Updated, activityVerb("Updated"))
        assertEquals(ActivityVerb.Deleted, activityVerb("deleted"))
        assertEquals(ActivityVerb.Uploaded, activityVerb("uploaded"))
        assertEquals(ActivityVerb.Removed, activityVerb("removed"))
        assertEquals(ActivityVerb.Changed, activityVerb("step_updated"))
    }

    @Test
    fun `a project row opens the project, a yarn row the yarn`() {
        assertEquals(ActivityTarget.Project(17), activityRow(item()).target)
        assertEquals(ActivityTarget.Yarn(17), activityRow(item(type = "yarn")).target)
    }

    @Test
    fun `a deleted resource is muted and not tappable and drops its thumbnail`() {
        val row = activityRow(item(action = "deleted", exists = false))
        assertNull(row.target)
        assertTrue(row.deleted)
        assertNull(row.thumbnailUrl)
    }

    @Test
    fun `the actor is named only when it is somebody else`() {
        assertNull(activityRow(item(self = true)).actorName)
        assertEquals("anna", activityRow(item(self = false, actorName = "anna")).actorName)
        assertNull(activityRow(item(self = false, actorName = null)).actorName)
        assertTrue(activityRow(item(self = true)).actorIsSelf)
    }

    @Test
    fun `changed fields become distinct nouns, capped with a remainder`() {
        val row =
            activityRow(
                item(
                    fields =
                        listOf(
                            "project.name",
                            "project.needles",
                            "project.notes",
                            "project.tags",
                            "project.steps",
                            "project.unknown_field",
                        )
                )
            )
        assertEquals(
            listOf(
                R.string.history_noun_name,
                R.string.history_noun_needles,
                R.string.history_noun_notes,
            ),
            row.fieldNounRes,
        )
        assertEquals(2, row.moreFields)
        assertEquals(emptyList<Int>(), activityRow(item(fields = listOf("nope"))).fieldNounRes)
    }

    @Test
    fun `yarn and project name fields share a noun without repeating`() {
        val row = activityRow(item(fields = listOf("project.name", "yarn.name")))
        assertEquals(listOf(R.string.history_noun_name), row.fieldNounRes)
    }

    @Test
    fun `count is at least one and a blank name is unnamed`() {
        assertEquals(1, activityRow(item(count = 0)).count)
        assertEquals(4, activityRow(item(count = 4)).count)
        assertNull(activityRow(item(name = "  ")).entityName)
    }

    @Test
    fun `the name is found by its last occurrence`() {
        assertEquals(12..23, nameRange("You updated Winter socks", "Winter socks"))
        assertEquals(9..11, nameRange("Sox said Sox", "Sox"))
        assertNull(nameRange("You updated a project", null))
        assertNull(nameRange("You updated", "Winter socks"))
    }

    @Test
    fun `merging pages drops items already shown`() {
        val a = listOf(item(id = 5), item(id = 4))
        val b = listOf(item(id = 4), item(id = 3))
        assertEquals(listOf(5L, 4L, 3L), mergeActivity(a, b).map { it.id })
    }

    @Test
    fun `actions map to node icons, unknown ones by suffix`() {
        assertEquals(ActivityIconKind.Sparkle, activityIconKind("created"))
        assertEquals(ActivityIconKind.Pencil, activityIconKind("updated"))
        assertEquals(ActivityIconKind.Camera, activityIconKind("uploaded"))
        assertEquals(ActivityIconKind.Camera, activityIconKind("photo_uploaded"))
        assertEquals(ActivityIconKind.Attachment, activityIconKind("attachment_uploaded"))
        assertEquals(ActivityIconKind.Trash, activityIconKind("deleted"))
        assertEquals(ActivityIconKind.Minus, activityIconKind("removed"))
        assertEquals(ActivityIconKind.Sparkle, activityIconKind("pattern_created"))
        assertEquals(ActivityIconKind.Trash, activityIconKind("Pattern_Deleted"))
        assertEquals(ActivityIconKind.Other, activityIconKind("whatever"))
        assertEquals(ActivityIconKind.Camera, activityRow(item(action = "uploaded")).icon)
    }

    private val berlin = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 11, 3)

    @Test
    fun `days are grouped in the local zone`() {
        // 23:30 UTC on Nov 2 is already Nov 3 in Berlin, 00:10 UTC on Nov 3 is the same day.
        val days =
            groupActivity(
                listOf(
                    item(id = 5, at = "2026-11-03T09:00:00Z"),
                    item(id = 4, at = "2026-11-03T00:10:00Z", entityId = 2),
                    item(id = 3, at = "2026-11-02T23:30:00Z", entityId = 3),
                    item(id = 2, at = "2026-11-02T10:00:00Z"),
                    item(id = 1, at = "2026-02-14T10:00:00Z"),
                ),
                today,
                berlin,
            )
        assertEquals(
            listOf(DayLabel.Today, DayLabel.Yesterday, DayLabel.On(LocalDate.of(2026, 2, 14))),
            days.map { it.label },
        )
        assertEquals(listOf(3, 1, 1), days.map { it.rows.size })
    }

    @Test
    fun `an unreadable timestamp gets its own unknown day`() {
        val days =
            groupActivity(
                listOf(item(id = 2, at = "nope"), item(id = 1, at = "nope")),
                today,
                berlin,
            )
        assertEquals(listOf(DayLabel.Unknown), days.map { it.label })
    }

    @Test
    fun `the time of day is 24 hour in the zone`() {
        assertEquals("14:32", activityTimeOfDay("2026-11-03T13:32:00Z", berlin))
        assertEquals("09:05", activityTimeOfDay("2026-11-03T08:05:00Z", berlin))
        assertNull(activityTimeOfDay("nope", berlin))
    }

    @Test
    fun `neighbouring runs fold and unite their fields`() {
        val merged =
            mergeActivityRuns(
                listOf(
                    item(id = 30, count = 2, fields = listOf("project.notes")),
                    item(
                        id = 20,
                        at = "2026-11-03T07:00:00Z",
                        fields = listOf("project.notes", "project.needles"),
                    ),
                )
            )
        assertEquals(1, merged.size)
        assertEquals(30L, merged[0].id)
        assertEquals(3, merged[0].count)
        assertEquals("2026-11-03T07:00:00Z", merged[0].firstCreatedAt)
        assertEquals(listOf("project.notes", "project.needles"), merged[0].changedFields)
    }

    @Test
    fun `other items, actions and actors stay apart`() {
        assertEquals(
            3,
            mergeActivityRuns(
                    listOf(
                        item(id = 3),
                        item(id = 2, entityId = 99),
                        item(id = 1, action = "uploaded"),
                    )
                )
                .size,
        )
        assertEquals(
            2,
            mergeActivityRuns(listOf(item(id = 2), item(id = 1, self = false, actorName = "anna")))
                .size,
        )
        // Not neighbours: the run is broken by another item.
        assertEquals(
            3,
            mergeActivityRuns(listOf(item(id = 3), item(id = 2, entityId = 99), item(id = 1))).size,
        )
    }

    @Test
    fun `created and deleted never fold`() {
        assertEquals(
            2,
            mergeActivityRuns(
                    listOf(item(id = 2, action = "deleted"), item(id = 1, action = "deleted"))
                )
                .size,
        )
        assertEquals(
            2,
            mergeActivityRuns(
                    listOf(item(id = 2, action = "created"), item(id = 1, action = "created"))
                )
                .size,
        )
    }

    @Test
    fun `runs fold within a day but not across days`() {
        val days =
            groupActivity(
                listOf(
                    item(id = 3, at = "2026-11-03T09:00:00Z"),
                    item(id = 2, at = "2026-11-03T08:00:00Z"),
                    item(id = 1, at = "2026-11-02T09:00:00Z"),
                ),
                today,
                berlin,
            )
        assertEquals(listOf(1, 1), days.map { it.rows.size })
        assertEquals(2, days[0].rows[0].count)
    }
}
