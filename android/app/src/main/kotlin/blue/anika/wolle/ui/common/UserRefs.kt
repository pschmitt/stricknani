package blue.anika.wolle.ui.common

import blue.anika.wolle.data.api.dto.ActivityActorDto
import blue.anika.wolle.data.api.dto.ActivityItemDto
import blue.anika.wolle.data.api.dto.AuditEventDto
import blue.anika.wolle.data.api.dto.AuditSubjectDto

/*
 * The one place that turns server fields into the person a [UserLabel] shows. The web does the
 * same in `user-label.ts` (`historyActor`, `activityActor`, `subjectUser`).
 */

/** Short names only: an older server without `actor_name` still yields a name, never the email. */
internal fun nameFromEmail(email: String): String {
    val words =
        email
            .substringBefore('@')
            .split('.', '_', '-')
            .filter { it.isNotEmpty() }
            .map {
                it.replaceFirstChar { c -> c.uppercase() }
            }
    return if (words.isEmpty()) email else words.joinToString(" ")
}

/**
 * The actor of a history event: the viewer's own actions are [UserKind.Self], the server's
 * [UserKind.System], a row without an account [UserKind.Unknown], everybody else a [UserKind.User]
 * by display name.
 */
fun historyActorRef(event: AuditEventDto): UserRef {
    if (event.actorIsSelf) {
        return UserRef(UserKind.Self, event.actorName, event.actorAvatarUrl)
    }
    if (event.system) return UserRef(UserKind.System)
    val name =
        event.actorName?.trim()?.takeIf { it.isNotEmpty() }
            ?: event.actorEmail?.takeIf { it.isNotBlank() }?.let(::nameFromEmail)
    return if (name == null) UserRef(UserKind.Unknown)
    else UserRef(UserKind.User, name, event.actorAvatarUrl)
}

/** The actor of a feed item; a server without `kind` is read from `isSelf`, `name` and `system`. */
fun activityActorRef(actor: ActivityActorDto, system: Boolean): UserRef {
    val kind =
        parseUserKind(actor.kind)
            ?: when {
                actor.isSelf -> UserKind.Self
                system -> UserKind.System
                !actor.name.isNullOrBlank() -> UserKind.User
                else -> UserKind.Admin
            }
    val shown = kind == UserKind.Self || kind == UserKind.User
    return UserRef(kind, if (shown) actor.name else null, if (shown) actor.avatarUrl else null)
}

fun activityActorRef(item: ActivityItemDto): UserRef = activityActorRef(item.actor, item.system)

/**
 * The other account of a sharing event or an "Owner: …" hint; [UserKind.Deleted] without a name.
 */
fun subjectRef(subject: AuditSubjectDto?): UserRef? {
    if (subject == null) return null
    val name = subject.name?.trim()?.takeIf { it.isNotEmpty() }
    return if (name == null) UserRef(UserKind.Deleted)
    else UserRef(UserKind.User, name, subject.avatarUrl)
}

/** The administrator behind an impersonated change: named when known, else generic. */
fun impersonatorRef(name: String?): UserRef =
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { UserRef(UserKind.User, it) }
        ?: UserRef(UserKind.Admin)
