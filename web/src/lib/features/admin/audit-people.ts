import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
import { accountUser, avatarPath, type UserRef } from "#lib/user-label.ts";
import type { AuditEvent, AuditFilterUser, Impersonator } from "./admin-api";
import { auditUserRole } from "./audit-user-role";

/**
 * The people of an administrator audit row as `UserRef`s for the shared `UserLabel`. The audit
 * always carries emails; the label shows the display name derived from it and the avatar, and
 * the emails stay in the data for the filters.
 */

const legacyUserMessage = msg("Legacy user");

/** A sentence with `{placeholders}` and the people that fill them. */
export interface PeopleSentence {
  template: string;
  users: Record<string, UserRef>;
}

/** Who caused the event: the account, the server's own actor, the purge job, or a legacy row. */
export function auditActor(event: AuditEvent, locale: Locale): UserRef {
  if (event.actor_email)
    return accountUser({
      email: event.actor_email,
      avatar_url: event.actor_avatar_url,
    });
  if (event.system) return { kind: "system", name: null, avatarUrl: null };
  if (event.server?.actor_email)
    return accountUser({ email: event.server.actor_email });
  if (event.legacy_actor_user_id !== null)
    return {
      kind: "user",
      name: `${t(legacyUserMessage, locale)} #${event.legacy_actor_user_id}`,
      avatarUrl: null,
    };
  return { kind: "unknown", name: null, avatarUrl: null };
}

/** The administrator behind a change made while acting as another account. */
export function auditImpersonator(admin: Impersonator): UserRef {
  if (admin.email)
    return accountUser({
      email: admin.email,
      name: admin.name,
      avatar_url: admin.avatar_url,
    });
  return admin.name
    ? {
        kind: "user",
        name: admin.name,
        avatarUrl: avatarPath(admin.avatar_url),
      }
    : { kind: "admin", name: null, avatarUrl: null };
}

/** "by {admin} as {user}", or null when nobody impersonated. */
export function auditImpersonatedBy(
  event: AuditEvent,
  actor: UserRef,
  locale: Locale,
): PeopleSentence | null {
  if (!event.impersonated_by) return null;
  return {
    template: t("by {admin} as {user}", locale),
    users: { admin: auditImpersonator(event.impersonated_by), user: actor },
  };
}

/** "Impersonation by {admin} started": the headline of the two impersonation events. */
export function impersonationTemplate(
  action: string,
  locale: Locale,
): string | null {
  return action === "impersonation_started"
    ? t("Impersonation by {admin} started", locale)
    : action === "impersonation_stopped"
      ? t("Impersonation by {admin} stopped", locale)
      : null;
}

/** What the filtered account did in an event: "By {user}", "{user} acting as {actor}", ... */
export function auditUserRoleSentence(
  event: AuditEvent,
  user: AuditFilterUser,
  actor: UserRef,
  locale: Locale,
): PeopleSentence | null {
  const role = auditUserRole(event, user);
  const person = accountUser(user);
  switch (role) {
    case "actor":
      return {
        template: t("By {user}", locale),
        users: { user: person, actor },
      };
    case "impersonator":
      return {
        template: t("{user} acting as {actor}", locale),
        users: { user: person, actor },
      };
    case "subject":
      return {
        template:
          event.entity_type === "user"
            ? t("On {user}'s account", locale)
            : t("Affects {user}", locale),
        users: { user: person, actor },
      };
    default:
      return null;
  }
}
