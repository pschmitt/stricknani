package blue.anika.wolle.ui.trash

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.OwnerDto
import blue.anika.wolle.data.api.dto.TrashItemDto
import blue.anika.wolle.data.api.dto.TrashParentDto
import blue.anika.wolle.data.repository.TrashFailure
import blue.anika.wolle.data.repository.TrashKind
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.repository.trashFailureFor
import blue.anika.wolle.data.repository.trashSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashPresentationTest {
    @Test
    fun `every kind has its plural segment and a label, an unknown kind falls back`() {
        val segments =
            mapOf(
                "project" to "projects",
                "yarn" to "yarns",
                "category" to "categories",
                "step" to "steps",
                "image" to "images",
                "attachment" to "attachments",
                "photo" to "photos",
            )
        segments.forEach { (kind, segment) ->
            assertEquals(segment, trashSegment(kind))
            assertEquals(segment, TrashRef(kind, 1).segment)
        }
        assertEquals(TrashKind.entries.size, segments.size)
        assertEquals(R.string.trash_kind_step, trashKindLabelRes("step"))
        assertEquals(R.string.trash_kind_photo, trashKindLabelRes("photo"))
        assertEquals(R.string.trash_kind_item, trashKindLabelRes("hologram"))
        assertEquals("holograms", trashSegment("hologram"))
        assertEquals(TrashRef("project", 4), TrashRef(TrashKind.Project, 4))
    }

    @Test
    fun `days left counts down to due for deletion at zero`() {
        assertEquals(DaysLeft.Days(90), daysLeft(90))
        assertEquals(DaysLeft.Days(1), daysLeft(1))
        assertEquals(DaysLeft.Due, daysLeft(0))
        assertEquals(DaysLeft.Due, daysLeft(-3))
    }

    @Test
    fun `a child row names its parent and detail, the owner only in the all scope`() {
        val item =
            TrashItemDto(
                kind = "step",
                id = 9,
                name = " Step 2 ",
                owner = OwnerDto(id = 2, email = "a@example.com", name = "Anika"),
                deletedAt = "2026-11-02T10:00:00Z",
                daysRemaining = 5,
                parent = TrashParentDto("yarn", 3, "Merino"),
                detail = "  ",
            )

        val mine = trashRow(item, showOwner = false)
        assertEquals("Step 2", mine.name)
        assertEquals("yarn", mine.parentKind)
        assertEquals("Merino", mine.parentName)
        assertNull(mine.detail)
        assertNull(mine.ownerName)
        assertEquals(DaysLeft.Days(5), mine.daysLeft)
        assertEquals(R.string.trash_parent_yarn, trashParentRes("yarn"))
        assertEquals(R.string.trash_parent_project, trashParentRes("project"))

        assertEquals("Anika", trashRow(item, showOwner = true).ownerName)
        assertNull(trashRow(item.copy(owner = null), showOwner = true).ownerName)
    }

    @Test
    fun `the list shows the newest deletion first and keeps the server order on ties`() {
        fun item(id: Long, at: String?) = TrashItemDto(kind = "project", id = id, deletedAt = at)

        val sorted =
            sortTrash(
                listOf(
                    item(1, "2026-11-01T10:00:00Z"),
                    item(2, "2026-11-03T10:00:00Z"),
                    item(3, "2026-11-03T10:00:00Z"),
                    item(4, null),
                )
            )

        assertEquals(listOf(2L, 3L, 1L, 4L), sorted.map { it.id })
    }

    @Test
    fun `server errors map to failures and the right restore message`() {
        assertEquals(TrashFailure.ParentInTrash, trashFailureFor(409, "parent_in_trash"))
        assertEquals(TrashFailure.Other, trashFailureFor(409, "conflict"))
        assertEquals(TrashFailure.NameTaken, trashFailureFor(409, "name_taken"))
        assertEquals(R.string.error_trash_name_taken, restoreFailureRes(TrashFailure.NameTaken))
        assertEquals(TrashFailure.NotFound, trashFailureFor(404, null))
        assertEquals(TrashFailure.NotAllowed, trashFailureFor(403, null))
        assertEquals(TrashFailure.Other, trashFailureFor(500, null))

        assertEquals(
            R.string.error_trash_restore_parent,
            restoreFailureRes(TrashFailure.ParentInTrash),
        )
        assertEquals(R.string.error_trash_gone, restoreFailureRes(TrashFailure.NotFound))
        assertEquals(R.string.error_trash_offline, restoreFailureRes(TrashFailure.Offline))
        assertEquals(R.string.error_trash_restore_failed, restoreFailureRes(TrashFailure.Other))
        assertEquals(R.string.error_trash_gone, purgeFailureRes(TrashFailure.NotFound))
        assertEquals(R.string.error_trash_purge_failed, purgeFailureRes(TrashFailure.Other))
    }

    @Test
    fun `days until purge rounds up and never goes negative`() {
        val now = java.time.Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        assertEquals(5, daysUntilPurge("2026-11-06T12:00:00Z", now))
        assertEquals(6, daysUntilPurge("2026-11-06T12:00:01Z", now))
        assertEquals(1, daysUntilPurge("2026-11-01T12:00:05Z", now))
        assertEquals(0, daysUntilPurge("2026-10-30T12:00:00Z", now))
        assertNull(daysUntilPurge(null, now))
        assertNull(daysUntilPurge("garbage", now))
    }
}
