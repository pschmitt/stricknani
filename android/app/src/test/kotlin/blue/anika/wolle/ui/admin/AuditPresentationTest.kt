package blue.anika.wolle.ui.admin

import androidx.compose.ui.graphics.Color
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AdminUserDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditMediaDto
import blue.anika.wolle.data.api.dto.AuditResourceDto
import blue.anika.wolle.data.api.dto.ChangeStep
import blue.anika.wolle.data.api.dto.ChangeValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditPresentationTest {
    private fun event(id: Long, createdAt: String, action: String = "updated") =
        AuditEventDto(
            id = id,
            entityType = "project",
            entityId = 1,
            action = action,
            createdAt = createdAt,
        )

    @Test
    fun `known actions map to the web icon kinds`() {
        assertEquals(AuditActionKind.Create, auditActionPresentation("created").kind)
        assertEquals(AuditActionKind.Create, auditActionPresentation("step_created").kind)
        assertEquals(AuditActionKind.Update, auditActionPresentation("updated").kind)
        assertEquals(AuditActionKind.Update, auditActionPresentation("photo_promoted").kind)
        assertEquals(AuditActionKind.Update, auditActionPresentation("title_image_promoted").kind)
        assertEquals(AuditActionKind.Delete, auditActionPresentation("deleted").kind)
        assertEquals(AuditActionKind.Delete, auditActionPresentation("attachment_deleted").kind)
        assertEquals(AuditActionKind.Upload, auditActionPresentation("uploaded").kind)
        assertEquals(AuditActionKind.Upload, auditActionPresentation("photo_uploaded").kind)
        assertEquals(AuditActionKind.Remove, auditActionPresentation("removed").kind)
        assertNotNull(auditActionPresentation("created").labelRes)
    }

    @Test
    fun `unknown actions are muted and get a readable label`() {
        val presentation = auditActionPresentation("yarn_archived")
        assertEquals(AuditActionKind.Other, presentation.kind)
        assertNull(presentation.labelRes)
        assertEquals("Yarn archived", presentation.fallbackLabel)
    }

    @Test
    fun `every known action name has a label and a kind`() {
        knownAuditActionNames.forEach { name ->
            val presentation = auditActionPresentation(name)
            assertNotNull(name, presentation.labelRes)
            assertTrue(name, presentation.kind != AuditActionKind.Other)
        }
    }

    @Test
    fun `tones follow the colour rules`() {
        assertEquals(AuditTone.Green, AuditActionKind.Create.tone())
        assertEquals(AuditTone.Blue, AuditActionKind.Update.tone())
        assertEquals(AuditTone.Red, AuditActionKind.Delete.tone())
        assertEquals(AuditTone.Red, AuditActionKind.Remove.tone())
        assertEquals(AuditTone.Marigold, AuditActionKind.Upload.tone())
        assertEquals(AuditTone.Muted, AuditActionKind.Other.tone())
    }

    @Test
    fun `update blue uses the specified light and dark colours`() {
        assertEquals(Color(0xFF1D5FB8), UpdateBlueLight)
        assertEquals(Color(0xFFA8C8FF), UpdateBlueDark)
    }

    @Test
    fun `events are grouped into today, yesterday and older days in the zone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val today = LocalDate.of(2026, 11, 3)
        val groups =
            groupEventsByDay(
                listOf(
                    event(1, "2026-11-03T09:00:00Z"),
                    event(2, "2026-11-03T08:00:00+00:00"),
                    event(3, "2026-11-02T10:00:00Z"),
                    // 23:30 UTC on the 1st is already the 2nd in Berlin: still yesterday's group.
                    event(4, "2026-11-01T23:30:00Z"),
                    event(5, "2026-10-30T12:00:00"),
                    event(6, "garbage"),
                ),
                today,
                zone,
            )
        assertEquals(
            listOf(
                DayLabel.Today,
                DayLabel.Yesterday,
                DayLabel.On(LocalDate.of(2026, 10, 30)),
                DayLabel.Unknown,
            ),
            groups.map { it.label },
        )
        assertEquals(
            listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L), listOf(6L)),
            groups.map { g -> g.events.map { it.id } },
        )
    }

    @Test
    fun `naive timestamps are treated as UTC`() {
        val instant = auditEventInstant(event(1, "2026-11-02T18:30:05"))
        assertEquals(Instant.parse("2026-11-02T18:30:05Z"), instant)
        assertEquals(
            DayLabel.Today,
            dayLabelFor(instant, LocalDate.of(2026, 11, 2), ZoneOffset.UTC),
        )
    }

    @Test
    fun `empty input has no groups`() {
        assertTrue(
            groupEventsByDay(emptyList(), LocalDate.of(2026, 1, 1), ZoneOffset.UTC).isEmpty()
        )
    }

    @Test
    fun `change values render as plain text`() {
        val s: (Int) -> String = { "res$it" }
        assertEquals("Hat", ChangeValue.Text("Hat").displayText(s))
        assertEquals(
            "res${R.string.admin_value_not_provided}",
            ChangeValue.Text(null).displayText(s),
        )
        assertEquals(
            "res${R.string.admin_value_empty_list}",
            ChangeValue.TextList(emptyList()).displayText(s),
        )
        assertEquals("a\nb", ChangeValue.TextList(listOf("a", "b")).displayText(s))
        assertEquals("4", ChangeValue.Integer(4).displayText(s))
        assertEquals("res${R.string.admin_value_yes}", ChangeValue.Flag(true).displayText(s))
        assertEquals(
            "res${R.string.admin_value_link_present}",
            ChangeValue.LinkChanged(true).displayText(s),
        )
        assertEquals(
            "1. Cast on\n\n2. Knit\nRibbing",
            ChangeValue.Steps(
                    listOf(ChangeStep(1, "Cast on", null), ChangeStep(2, "Knit", "Ribbing"))
                )
                .displayText(s),
        )
    }

    @Test
    fun `fields and unavailable reasons have labels`() {
        assertEquals(R.string.admin_field_project_name, changeFieldLabelRes("project.name"))
        assertNull(changeFieldLabelRes("project.nonsense"))
        assertEquals(R.string.admin_changes_not_recorded, unavailableReasonRes("not_recorded"))
        assertEquals(R.string.admin_changes_unavailable, unavailableReasonRes(null))
    }

    @Test
    fun `initials come from the first letter or digit`() {
        assertEquals("A", initialOf("anna@example.org"))
        assertEquals("?", initialOf(null))
        assertEquals("7", initialOf(" 7up@example.org"))
    }

    @Test
    fun `generated passwords are long enough and avoid look alike characters`() {
        val password = generateTemporaryPassword(random = Random(1))
        assertTrue(password.length >= MIN_PASSWORD_LENGTH)
        assertTrue(password.none { it in "0O1lI" })
        assertEquals(MIN_PASSWORD_LENGTH, generateTemporaryPassword(length = 3).length)
    }

    @Test
    fun `current user is matched by email ignoring case`() {
        val user = AdminUserDto(1, "Anna@Example.org", active = true, isAdmin = true)
        assertTrue(isCurrentUser(user, "anna@example.org"))
        assertEquals(false, isCurrentUser(user, null))
        assertEquals(false, isCurrentUser(user, "bob@example.org"))
    }

    private fun resourceEvent(
        kind: String?,
        exists: Boolean,
        id: Long = 7,
        media: AuditMediaDto? = null,
        hasChanges: Boolean = false,
    ) =
        AuditEventDto(
            id = 1,
            entityType = kind ?: "project",
            entityId = id,
            action = "updated",
            createdAt = "2026-11-02T18:30:05Z",
            resource = AuditResourceDto(kind = kind, id = id, exists = exists),
            hasChanges = hasChanges,
            media = media,
        )

    @Test
    fun `tapping a project or yarn event opens the item, deleted ones say so`() {
        assertEquals(AuditTarget.Project(7), resourceEvent("project", true).target())
        assertEquals(AuditTarget.Yarn(7), resourceEvent("yarn", true).target())
        assertEquals(AuditTarget.Deleted, resourceEvent("project", false).target())
        assertEquals(AuditTarget.Deleted, resourceEvent("yarn", false).target())
        assertEquals(AuditTarget.None, resourceEvent("category", true).target())
        assertEquals(AuditTarget.None, resourceEvent(null, false).copy(entityType = "x").target())
    }

    @Test
    fun `media state distinguishes pictures, files and deleted media`() {
        val picture =
            AuditMediaDto(
                kind = "image",
                id = 3,
                name = "front.png",
                exists = true,
                isImage = true,
                url = "/api/v2/admin/resources/project/7/media/image/3",
                thumbnailUrl = "/api/v2/admin/resources/project/7/media/image/3/thumbnail",
            )
        assertTrue(picture.state() is AuditMediaState.Picture)
        val file =
            AuditMediaDto(
                kind = "attachment",
                id = 4,
                name = "chart.pdf",
                exists = true,
                url = "/media/projects/7/x.pdf",
            )
        assertTrue(file.state() is AuditMediaState.File)
        val gone = picture.copy(exists = false, url = null, thumbnailUrl = null)
        assertEquals(AuditMediaState.Deleted(isImage = true), gone.state())
        // A server that says it exists but sends no link is treated as deleted, never a dead link.
        assertEquals(
            AuditMediaState.Deleted(isImage = false),
            file.copy(url = null).state(),
        )
        assertEquals("front.png", picture.displayName())
        assertNull(picture.copy(name = "  ").displayName())
    }

    @Test
    fun `every event offers media, changes or an explicit no details state`() {
        val media = AuditMediaDto(kind = "photo", id = 1, exists = true, isImage = true, url = "/x")
        assertEquals(AuditDetailKind.Media, resourceEvent("yarn", true, media = media).detailKind())
        assertEquals(
            AuditDetailKind.Changes,
            resourceEvent("project", true, hasChanges = true).detailKind(),
        )
        assertEquals(AuditDetailKind.None, resourceEvent("project", true).detailKind())
    }

    @Test
    fun `new change fields have labels`() {
        listOf(
                "project.cover_image",
                "project.image_order",
                "project.step_image_order",
                "yarn.primary_photo",
                "yarn.photo_order",
            )
            .forEach { assertNotNull(it, changeFieldLabelRes(it)) }
    }

    @Test
    fun `cached file names are safe`() {
        val media = AuditMediaDto(id = 9, name = "../../etc/pass wd?.pdf")
        val name = safeAuditFileName(media)
        assertTrue(name.startsWith("9-"))
        assertTrue(name.none { it == '/' || it == '\\' || it == ' ' || it == '?' })
        assertEquals("5-file", safeAuditFileName(AuditMediaDto(id = 5, name = null)))
    }
}
