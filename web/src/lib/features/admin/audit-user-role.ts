import type { Locale } from "#lib/i18n.svelte.ts";
import { t } from "#lib/i18n.svelte.ts";
import type { AuditEvent, AuditFilterUser } from "./admin-api";

/** What the filtered account did in an event, as seen on the user's activity view. */
export type AuditUserRole = "actor" | "impersonator" | "subject" | null;

/**
 * The part `user` played in `event`. The actor wins over the impersonator, who
 * wins over the subject. The actor is matched by email, which the administrator
 * audit always carries.
 */
export function auditUserRole(
  event: AuditEvent,
  user: AuditFilterUser,
): AuditUserRole {
  if (event.actor_email !== null && event.actor_email === user.email)
    return "actor";
  if (event.impersonated_by?.id === user.id) return "impersonator";
  if (event.subject?.id === user.id) return "subject";
  return null;
}

/** "by Ann", "Ann as Bea", "on Ann's account" or "Ann is affected". */
export function auditUserRoleText(
  event: AuditEvent,
  user: AuditFilterUser,
  actor: string,
  locale: Locale,
): string | null {
  const role = auditUserRole(event, user);
  const fill = (template: string) =>
    template.replace("{user}", user.email).replace("{actor}", actor);
  switch (role) {
    case "actor":
      return fill(t("By {user}", locale));
    case "impersonator":
      return fill(t("{user} acting as {actor}", locale));
    case "subject":
      return fill(
        event.entity_type === "user"
          ? t("On {user}'s account", locale)
          : t("Affects {user}", locale),
      );
    default:
      return null;
  }
}
