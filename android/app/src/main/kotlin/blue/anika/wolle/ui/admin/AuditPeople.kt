package blue.anika.wolle.ui.admin

import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditFilterUserDto
import blue.anika.wolle.ui.common.UserKind
import blue.anika.wolle.ui.common.UserRef
import blue.anika.wolle.ui.common.accountRef

/*
 * The people of an administrator audit row as the [UserRef]s of the shared UserLabel. The audit
 * always carries emails; the label shows the display name derived from it with the avatar. Web
 * twin: `audit-people.ts`.
 */

/** Who caused the event: the account, the server's own actor, the purge job, or nobody known. */
fun AuditEventDto.auditActorRef(): UserRef {
    actorEmail
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return accountRef(it, avatarUrl = actorAvatarUrl)
        }
    if (system) return UserRef(UserKind.System)
    server
        ?.actorEmail
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return accountRef(it)
        }
    return UserRef(UserKind.Unknown)
}

/** The account an audit list is filtered to. */
fun AuditFilterUserDto.ref(): UserRef = accountRef(email, avatarUrl = avatarUrl)
