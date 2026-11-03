package blue.anika.wolle.ui.home

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ActivityActorDto
import blue.anika.wolle.data.api.dto.ActivityItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPresentationTest {
    private fun item(
        id: Long = 1,
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
            createdAt = "2026-11-03T10:09:12.000000Z",
            action = action,
            entityType = type,
            entityId = 17,
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
}
