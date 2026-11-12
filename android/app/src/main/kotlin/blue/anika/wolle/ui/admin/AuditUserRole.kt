package blue.anika.wolle.ui.admin

import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditFilterUserDto

/** What the filtered account did in an event (web twin: `audit-user-role.ts`). */
enum class AuditUserRole {
    Actor,
    Impersonator,
    SubjectOfAccount,
    Subject,
}

/**
 * The part [user] played in this event: the actor wins over the impersonator, who wins over the
 * subject. The actor is matched by email, which the administrator audit always carries.
 */
fun AuditEventDto.userRole(user: AuditFilterUserDto): AuditUserRole? =
    when {
        actorEmail != null && actorEmail == user.email -> AuditUserRole.Actor
        impersonatedBy?.id == user.id -> AuditUserRole.Impersonator
        subject?.id == user.id ->
            if (entityType == "user") AuditUserRole.SubjectOfAccount else AuditUserRole.Subject
        else -> null
    }
