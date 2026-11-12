import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
import { nameFromEmail } from "#lib/owner.ts";

/**
 * Who a name on screen stands for. One vocabulary for every actor and owner hint, shared by the
 * Android `UserLabel` (`ui/common/UserLabel.kt`, `UserRef`) so both read alike.
 *
 * - `self`: the signed-in viewer ("You").
 * - `user`: another account, by display name (never an email).
 * - `admin`: an administrator whose identity is hidden from this viewer ("an administrator").
 * - `system`: no account caused it: the purge job, a restore the server ran ("System").
 * - `unknown`: a legacy row without a readable actor ("Unknown").
 */
export type UserKind = "self" | "user" | "admin" | "system" | "unknown";

export interface UserRef {
  kind: UserKind;
  /** Display name for `user` (and `self`, where the label says "You" instead). */
  name: string | null;
  /** Same-origin avatar path; null renders the initial (or the kind's icon). */
  avatarUrl: string | null;
}

const KINDS: readonly string[] = ["self", "user", "admin", "system", "unknown"];

export function parseUserKind(value: unknown): UserKind | null {
  return typeof value === "string" && KINDS.includes(value)
    ? (value as UserKind)
    : null;
}

/** Only same-origin paths are rendered as images. */
export function avatarPath(value: unknown): string | null {
  return typeof value === "string" &&
    value.startsWith("/") &&
    !value.startsWith("//")
    ? value
    : null;
}

const adminLabel = msg("an administrator");

/** "You", the display name, "an administrator", "System" or "Unknown". */
export function userLabelText(user: UserRef, locale?: Locale): string {
  switch (user.kind) {
    case "self":
      return t("You", locale);
    case "admin":
      return t(adminLabel, locale);
    case "system":
      return t("System", locale);
    case "unknown":
      return t("Unknown", locale);
    case "user":
      return user.name?.trim() || t("Unknown", locale);
  }
}

/**
 * "by You", "by Anika Bergmann", "by an administrator", "by System": the actor of an entry as a
 * phrase. Separate messages so German can decline them ("von dir", "von einem Administrator").
 */
export function userByText(user: UserRef, locale?: Locale): string {
  switch (user.kind) {
    case "self":
      return t("by You", locale);
    case "admin":
      return t("by an administrator", locale);
    case "system":
      return t("by System", locale);
    case "unknown":
      return t("by Unknown", locale);
    case "user":
      return `${t("by", locale)} ${userLabelText(user, locale)}`;
  }
}

/** A piece of a sentence: plain text, or a person to render as a `UserLabel`. */
export type SentencePart =
  { text: string; user?: undefined } | { user: UserRef; text?: undefined };

/**
 * Splits a translated template such as "by {admin} as {user}" or "Owner: {name}" at its
 * `{placeholder}`s so each person can be shown with avatar and name. A placeholder without a
 * person stays literal text.
 */
export function sentenceParts(
  template: string,
  users: Record<string, UserRef>,
): SentencePart[] {
  const parts: SentencePart[] = [];
  for (const piece of template.split(/(\{[a-z_]+\})/)) {
    if (piece === "") continue;
    const user = piece.startsWith("{") ? users[piece.slice(1, -1)] : undefined;
    parts.push(user ? { user } : { text: piece });
  }
  return parts;
}

/** The fields of a history event that name its actor. */
export interface HistoryActorFields {
  actor_is_self?: boolean;
  actor_name?: string | null;
  actor_email: string | null;
  actor_avatar_url: string | null;
  system?: boolean;
}

/**
 * The actor of a history event. The viewer's own actions are `self`, the server's `system`; a row
 * without an account is `unknown`. Names come from the server's display name; an older server
 * without one falls back to the shared email-derived name, never the email itself.
 */
export function historyActor(event: HistoryActorFields): UserRef {
  if (event.actor_is_self === true)
    return {
      kind: "self",
      name: event.actor_name ?? null,
      avatarUrl: event.actor_avatar_url,
    };
  if (event.system === true)
    return { kind: "system", name: null, avatarUrl: null };
  const name =
    event.actor_name?.trim() ||
    (event.actor_email ? nameFromEmail(event.actor_email) : null);
  if (!name) return { kind: "unknown", name: null, avatarUrl: null };
  return { kind: "user", name, avatarUrl: event.actor_avatar_url };
}

/** The `actor` object of an activity item. */
export interface ActivityActorFields {
  kind: UserKind | null;
  name: string | null;
  is_self: boolean;
  avatar_url: string | null;
}

/** The actor of an activity item; a server without `kind` is read from `is_self` and `name`. */
export function activityActor(
  actor: ActivityActorFields,
  system = false,
): UserRef {
  const kind =
    actor.kind ??
    (actor.is_self
      ? "self"
      : system
        ? "system"
        : actor.name
          ? "user"
          : "admin");
  return {
    kind,
    name: kind === "self" || kind === "user" ? actor.name : null,
    avatarUrl: kind === "self" || kind === "user" ? actor.avatar_url : null,
  };
}

/** The other account a sharing event or an "Owner: …" hint names; a deleted account has no name. */
export function subjectUser(
  subject: { name: string | null; avatar_url?: string | null } | null,
  locale?: Locale,
): UserRef | null {
  if (!subject) return null;
  return {
    kind: "user",
    name: subject.name?.trim() || t("a deleted account", locale),
    avatarUrl: avatarPath(subject.avatar_url),
  };
}
