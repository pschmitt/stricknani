package blue.anika.wolle.ui.admin

import androidx.compose.ui.graphics.Color
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AdminUserDto
import blue.anika.wolle.data.api.dto.AuditClientDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditMediaDto
import blue.anika.wolle.data.api.dto.AuditResourceDto
import blue.anika.wolle.data.api.dto.AuditSubjectDto
import blue.anika.wolle.data.api.dto.ChangeStep
import blue.anika.wolle.data.api.dto.ChangeValue
import blue.anika.wolle.data.api.dto.RESOURCE_STATE_ACTIVE
import blue.anika.wolle.data.api.dto.RESOURCE_STATE_GONE
import blue.anika.wolle.data.api.dto.RESOURCE_STATE_IN_TRASH
import blue.anika.wolle.data.api.dto.TrashStateDto
import blue.anika.wolle.data.repository.TrashRef
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
        assertEquals(
            AuditActionKind.Impersonate,
            auditActionPresentation("impersonation_started").kind,
        )
        assertEquals(
            AuditActionKind.Impersonate,
            auditActionPresentation("impersonation_stopped").kind,
        )
        assertNotNull(auditActionPresentation("impersonation_started").labelRes)
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
        assertEquals(AuditTone.Muted, AuditActionKind.Impersonate.tone())
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
        // Timer seconds read as a length of time, not a raw number.
        assertEquals(
            "25 min",
            ChangeValue.Integer(1500).displayText(s, "project.timer_seconds"),
        )
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

    private fun trashEvent(
        action: String,
        state: String? = null,
        exists: Boolean = false,
        canRestore: Boolean = false,
        system: Boolean = false,
        subject: AuditSubjectDto? = null,
        kind: String? = "project",
    ) =
        AuditEventDto(
            id = 9,
            entityType = "project",
            entityId = 3,
            action = action,
            createdAt = "2026-11-01T10:00:00Z",
            resource =
                AuditResourceDto(
                    kind = kind,
                    id = 3,
                    name = "Socks",
                    exists = exists,
                    state = state,
                    trash =
                        if (state == RESOURCE_STATE_IN_TRASH) {
                            TrashStateDto(daysRemaining = 12, canRestore = canRestore)
                        } else null,
                ),
            system = system,
            subject = subject,
        )

    @Test
    fun `trash actions have their own labels and icon kinds`() {
        assertEquals(R.string.admin_action_trashed, auditActionPresentation("trashed").labelRes)
        assertEquals(R.string.admin_action_restored, auditActionPresentation("restored").labelRes)
        assertEquals(R.string.admin_action_purged, auditActionPresentation("purged").labelRes)
        assertEquals(AuditActionKind.Delete, auditActionPresentation("trashed").kind)
        assertEquals(AuditActionKind.Restore, auditActionPresentation("restored").kind)
        assertEquals(AuditTone.Green, AuditActionKind.Restore.tone())
        // The legacy hard delete and a manual purge read the same.
        assertEquals(R.string.admin_action_deleted, auditActionPresentation("deleted").labelRes)
        assertTrue("trashed" in knownAuditActionNames)
        assertTrue("restored" in knownAuditActionNames)
        assertTrue("purged" in knownAuditActionNames)
    }

    @Test
    fun `only the automatic purge says purged after 90 days`() {
        assertEquals(
            R.string.admin_action_purged_auto,
            auditActionPresentation("purged", system = true).labelRes,
        )
        assertEquals(
            R.string.admin_action_purged,
            auditActionPresentation("purged", system = false).labelRes,
        )
        assertEquals(
            R.string.admin_action_trashed,
            auditActionPresentation("trashed", system = true).labelRes,
        )
    }

    @Test
    fun `the item state follows the server and falls back to exists for old payloads`() {
        assertEquals(AuditItemState.Active, AuditResourceDto(exists = true).itemState())
        assertEquals(AuditItemState.Gone, AuditResourceDto(exists = false).itemState())
        assertEquals(
            AuditItemState.Gone,
            AuditResourceDto(exists = false, state = RESOURCE_STATE_GONE).itemState(),
        )
        assertEquals(
            AuditItemState.InTrash(daysRemaining = 12, canRestore = true),
            trashEvent("trashed", RESOURCE_STATE_IN_TRASH, canRestore = true).resource.itemState(),
        )
        // In the trash without the trash block: zero days, not restorable.
        assertEquals(
            AuditItemState.InTrash(0, false),
            AuditResourceDto(state = RESOURCE_STATE_IN_TRASH).itemState(),
        )
    }

    @Test
    fun `an item in the trash does not link to its page`() {
        val inTrash = trashEvent("trashed", RESOURCE_STATE_IN_TRASH, canRestore = true)
        assertEquals(AuditTarget.InTrash(canRestore = true), inTrash.target())
        assertEquals(
            AuditTarget.InTrash(canRestore = false),
            trashEvent("trashed", RESOURCE_STATE_IN_TRASH).target(),
        )
        // A restored one is a normal item again.
        assertEquals(
            AuditTarget.Project(3),
            trashEvent("restored", RESOURCE_STATE_ACTIVE, exists = true).target(),
        )
        assertEquals(AuditTarget.Deleted, trashEvent("purged", RESOURCE_STATE_GONE).target())
        // Old payload: no state, still gone.
        assertEquals(AuditTarget.Deleted, trashEvent("deleted").target())
        // Other kinds in the trash lead to the trash too (no page of their own to 404).
        assertEquals(
            AuditTarget.InTrash(false),
            trashEvent("trashed", RESOURCE_STATE_IN_TRASH, kind = "category").target(),
        )
    }

    @Test
    fun `restore is offered only for a restorable item in the trash`() {
        assertEquals(
            TrashRef("project", 3),
            trashEvent("trashed", RESOURCE_STATE_IN_TRASH, canRestore = true).restoreRef(),
        )
        assertNull(trashEvent("trashed", RESOURCE_STATE_IN_TRASH).restoreRef())
        assertNull(trashEvent("restored", RESOURCE_STATE_ACTIVE, exists = true).restoreRef())
        assertNull(trashEvent("purged", RESOURCE_STATE_GONE).restoreRef())
    }

    @Test
    fun `lifecycle events name the owner, other events do not`() {
        val owner = AuditSubjectDto(id = 2, name = " Anika ", role = null)
        assertEquals("Anika", trashEvent("trashed", subject = owner).lifecycleOwnerName())
        assertEquals(
            "Anika",
            trashEvent("purged", system = true, subject = owner).lifecycleOwnerName(),
        )
        assertEquals("Anika", trashEvent("deleted", subject = owner).lifecycleOwnerName())
        assertNull(trashEvent("shared", subject = owner).lifecycleOwnerName())
        assertNull(trashEvent("trashed").lifecycleOwnerName())
        assertNull(
            trashEvent("trashed", subject = AuditSubjectDto(id = null, name = null))
                .lifecycleOwnerName()
        )
    }

    @Test
    fun `file and step events do not show the parent's trash state or a restore button`() {
        val step = trashEvent("step_trashed", RESOURCE_STATE_IN_TRASH, canRestore = true)
        assertNull(step.inTrashState())
        assertNull(step.restoreRef())
        assertEquals(AuditTarget.InTrash(false), step.target())
        assertEquals(
            R.string.admin_action_step_trashed,
            auditActionPresentation("step_trashed").labelRes,
        )
        assertEquals(AuditActionKind.Restore, auditActionPresentation("step_restored").kind)

        val file =
            trashEvent("trashed", RESOURCE_STATE_IN_TRASH, canRestore = true)
                .copy(media = AuditMediaDto(kind = "image", id = 5))
        assertNull(file.inTrashState())
        assertNull(file.restoreRef())
        assertEquals(AuditTarget.InTrash(false), file.target())
    }

    private val strings =
        mapOf(
            R.string.admin_client_system to "System",
            R.string.admin_client_unknown to "Unknown client",
            R.string.admin_client_web to "Web",
            R.string.admin_client_android to "Android",
            R.string.admin_client_api to "API",
        )

    private fun label(client: AuditClientDto?) = client.clientLabel { strings.getValue(it) }

    @Test
    fun `the client chip shows the server label, like the website`() {
        val firefox =
            AuditClientDto(kind = "web", label = "Firefox 159 \u00b7 Linux", userAgent = "Mozilla")
        assertEquals("Firefox 159 \u00b7 Linux", label(firefox))
        assertEquals(AuditClientKind.Web, firefox.clientKind())
        val app = AuditClientDto(kind = "android", label = "Android 1.7.1 (debug)")
        assertEquals("Android 1.7.1 (debug)", label(app))
        assertEquals(AuditClientKind.Android, app.clientKind())
    }

    @Test
    fun `the client chip names the kind when there is no label`() {
        assertEquals("Web", label(AuditClientDto(kind = "web")))
        assertEquals("Android", label(AuditClientDto(kind = "android", label = "  ")))
        assertEquals("API", label(AuditClientDto(kind = "api")))
        assertEquals("System", label(AuditClientDto(kind = "system")))
    }

    @Test
    fun `old events and unknown kinds read as an unknown client`() {
        assertEquals("Unknown client", label(null))
        assertEquals("Unknown client", label(AuditClientDto(kind = "unknown")))
        assertEquals("Unknown client", label(AuditClientDto(kind = "toaster", label = "x")))
        assertEquals(AuditClientKind.Unknown, null.clientKind())
    }

    @Test
    fun `a backup restore is a server event with its details`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val event =
            json.decodeFromString<AuditEventDto>(
                """{"id":7,"actor_email":null,"entity_type":"server","entity_id":0,
                "action":"backup_restored","created_at":"2026-01-02T03:04:05.000000Z",
                "resource":{"name":"stricknani-20260101-020000.tar.gz"},
                "server":{"backup":"stricknani-20260101-020000.tar.gz",
                "pre_restore":"pre-restore-20260102-030000.sqlite3","media":"add_missing",
                "actor_email":"admin@example.invalid"}}"""
            )
        assertEquals("add_missing", event.server?.media)
        assertEquals("pre-restore-20260102-030000.sqlite3", event.server?.preRestore)
        assertEquals("admin@example.invalid", event.server?.actorEmail)
        assertEquals(
            R.string.admin_action_backup_restored,
            auditActionPresentation(event.action).labelRes,
        )
        assertEquals(R.string.admin_entity_server, entityTypeLabelRes("server"))
        assertTrue("server" in auditEntityTypes)
        assertNull(event.copy(server = null).server)
    }
}
