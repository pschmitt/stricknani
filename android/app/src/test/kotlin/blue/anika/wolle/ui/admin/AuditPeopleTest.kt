package blue.anika.wolle.ui.admin

import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditFilterUserDto
import blue.anika.wolle.data.api.dto.AuditServerEventDto
import blue.anika.wolle.ui.common.UserKind
import blue.anika.wolle.ui.common.UserRef
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditPeopleTest {
    private fun event(
        actorEmail: String? = null,
        avatar: String? = null,
        system: Boolean = false,
        server: AuditServerEventDto? = null,
    ) =
        AuditEventDto(
            id = 1,
            actorEmail = actorEmail,
            actorAvatarUrl = avatar,
            entityType = "project",
            entityId = 17,
            action = "updated",
            createdAt = "2026-11-12T10:00:00Z",
            system = system,
            server = server,
        )

    @Test
    fun `the actor is shown by display name with the avatar, never the email`() {
        assertEquals(
            UserRef(UserKind.User, "Bea Lee", "/a/2"),
            event(actorEmail = "bea.lee@example.invalid", avatar = "/a/2").auditActorRef(),
        )
    }

    @Test
    fun `the purge job, the server's actor and a row without one`() {
        assertEquals(UserKind.System, event(system = true).auditActorRef().kind)
        assertEquals(
            "Root Admin",
            event(
                    server =
                        AuditServerEventDto(
                            backup = "b",
                            preRestore = "p",
                            actorEmail = "root.admin@example.invalid",
                        )
                )
                .auditActorRef()
                .name,
        )
        assertEquals(UserKind.Unknown, event().auditActorRef().kind)
    }

    @Test
    fun `the filtered account carries its avatar`() {
        assertEquals(
            UserRef(UserKind.User, "Ann Lee", "/a/7"),
            AuditFilterUserDto(7, "ann.lee@example.invalid", "/a/7").ref(),
        )
    }
}
