package blue.anika.wolle.ui.common

import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ActivityActorDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditSubjectDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserLabelTest {
    private fun event(
        actorEmail: String? = null,
        actorName: String? = null,
        self: Boolean = false,
        system: Boolean = false,
        avatar: String? = null,
    ) =
        AuditEventDto(
            id = 1,
            actorEmail = actorEmail,
            actorName = actorName,
            actorIsSelf = self,
            actorAvatarUrl = avatar,
            entityType = "project",
            entityId = 17,
            action = "timer_started",
            createdAt = "2026-11-12T10:00:00Z",
            system = system,
        )

    @Test
    fun `a label says You, the name, an administrator, System or Unknown`() {
        assertEquals(UserLabelText.You, userLabelText(UserRef(UserKind.Self, "Philipp")))
        assertEquals(
            UserLabelText.Name("Anika Bergmann"),
            userLabelText(UserRef(UserKind.User, " Anika Bergmann ")),
        )
        assertEquals(UserLabelText.Admin, userLabelText(UserRef(UserKind.Admin, "Leak")))
        assertEquals(UserLabelText.System, userLabelText(UserRef(UserKind.System)))
        assertEquals(UserLabelText.Unknown, userLabelText(UserRef(UserKind.Unknown)))
        assertEquals(UserLabelText.DeletedAccount, userLabelText(UserRef(UserKind.Deleted)))
        // A user without a readable name never shows an empty label.
        assertEquals(UserLabelText.Unknown, userLabelText(UserRef(UserKind.User, " ")))
    }

    @Test
    fun `author phrases are their own messages, a name uses by`() {
        assertEquals(R.string.user_by_you, userByRes(UserLabelText.You))
        assertEquals(R.string.user_by_admin, userByRes(UserLabelText.Admin))
        assertEquals(R.string.user_by_system, userByRes(UserLabelText.System))
        assertEquals(R.string.user_by_unknown, userByRes(UserLabelText.Unknown))
        assertNull(userByRes(UserLabelText.Name("Anika")))
    }

    @Test
    fun `the server's kinds parse and an unknown one does not`() {
        assertEquals(UserKind.Self, parseUserKind("self"))
        assertEquals(UserKind.Admin, parseUserKind("admin"))
        assertNull(parseUserKind("robot"))
        assertNull(parseUserKind(null))
    }

    @Test
    fun `the viewer's own history entries are You whatever the email says`() {
        assertEquals(
            UserRef(UserKind.Self, "Philipp", "/a.png"),
            historyActorRef(event(self = true, actorName = "Philipp", avatar = "/a.png")),
        )
    }

    @Test
    fun `history names people by display name, never by email`() {
        val actor =
            historyActorRef(
                event(
                    actorEmail = "anika.bergmann@mailbox.example",
                    actorName = "Anika Bergmann",
                    avatar = "/p.png",
                )
            )
        assertEquals(UserRef(UserKind.User, "Anika Bergmann", "/p.png"), actor)
        // An older server sends only the email: the shared fallback names it.
        assertEquals(
            "Anika Bergmann",
            historyActorRef(event(actorEmail = "anika.bergmann@mailbox.example")).name,
        )
    }

    @Test
    fun `history says System for the server and Unknown for a legacy row`() {
        assertEquals(UserKind.System, historyActorRef(event(system = true)).kind)
        assertEquals(UserKind.Unknown, historyActorRef(event()).kind)
        assertEquals(UserKind.Unknown, historyActorRef(event(actorName = "  ")).kind)
    }

    @Test
    fun `activity actors keep names and pictures for people only`() {
        assertEquals(
            UserRef(UserKind.Admin),
            activityActorRef(
                ActivityActorDto(kind = "admin", name = "Leak", avatarUrl = "/x.png"),
                system = false,
            ),
        )
        assertEquals(
            UserRef(UserKind.User, "Anna", "/x.png"),
            activityActorRef(
                ActivityActorDto(kind = "user", name = "Anna", avatarUrl = "/x.png"),
                system = false,
            ),
        )
        assertEquals(
            UserKind.System,
            activityActorRef(ActivityActorDto(), system = true).kind,
        )
    }

    @Test
    fun `subjects and impersonators are named or generic`() {
        assertNull(subjectRef(null))
        assertEquals(UserKind.Deleted, subjectRef(AuditSubjectDto(id = null, name = null))?.kind)
        assertEquals(
            UserRef(UserKind.User, "Anna", "/a.png"),
            subjectRef(AuditSubjectDto(id = 2, name = "Anna", avatarUrl = "/a.png")),
        )
        assertEquals(UserRef(UserKind.User, "Ann"), impersonatorRef("Ann"))
        assertEquals(UserRef(UserKind.Admin), impersonatorRef(null))
    }
}
