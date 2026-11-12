import { t, type Locale } from "#lib/i18n.svelte.ts";
import { daysLeftLabel } from "./trash-copy";

/** Where the resource of a lifecycle event is now. */
export type ResourceState = "active" | "in_trash" | "gone";

/** The trash details of a resource that is currently in the trash. */
export interface TrashStatus {
  deleted_at: string;
  purge_at: string;
  days_remaining: number;
  can_restore: boolean;
}

/** The owner of the resource, named on events an administrator or the system caused. */
export interface LifecycleSubject {
  id: number | null;
  name: string | null;
  avatar_url?: string | null;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** `state` of a resource or feed item; absent on older servers, `fail()` when malformed. */
export function parseResourceState(
  value: unknown,
  fail: () => Error,
): ResourceState | null {
  if (value === undefined || value === null) return null;
  if (value === "active" || value === "in_trash" || value === "gone")
    return value;
  throw fail();
}

/** `trash` of a resource; absent unless it is in the trash, `fail()` when malformed. */
export function parseTrashStatus(
  value: unknown,
  fail: () => Error,
): TrashStatus | null {
  if (value === undefined || value === null) return null;
  if (
    !record(value) ||
    typeof value.deleted_at !== "string" ||
    typeof value.purge_at !== "string" ||
    typeof value.days_remaining !== "number" ||
    !Number.isSafeInteger(value.days_remaining) ||
    value.days_remaining < 0 ||
    typeof value.can_restore !== "boolean"
  )
    throw fail();
  return {
    deleted_at: value.deleted_at,
    purge_at: value.purge_at,
    days_remaining: value.days_remaining,
    can_restore: value.can_restore,
  };
}

/** The optional `subject` of a lifecycle event; malformed values read as none. */
export function parseLifecycleSubject(value: unknown): LifecycleSubject | null {
  if (!record(value)) return null;
  const id = value.id;
  const name = typeof value.name === "string" ? value.name.trim() : "";
  return {
    id:
      typeof id === "number" && Number.isSafeInteger(id) && id > 0 ? id : null,
    name: name || null,
    avatar_url:
      typeof value.avatar_url === "string" &&
      value.avatar_url.startsWith("/") &&
      !value.avatar_url.startsWith("//")
        ? value.avatar_url
        : null,
  };
}

/** Actions that move a resource through the trash. */
export function isLifecycleAction(action: string): boolean {
  return action === "trashed" || action === "restored" || action === "purged";
}

/**
 * Where the item of a "moved to trash" entry is now: still in the trash (with the days left),
 * restored, or gone for good. Null for every other action, and without a known state.
 */
export function trashedStateText(
  action: string,
  state: ResourceState | null,
  trash: TrashStatus | null,
  locale?: Locale,
): string | null {
  if (action !== "trashed" || state === null) return null;
  if (state === "in_trash")
    return trash
      ? t("In the trash · {left}", locale).replace(
          "{left}",
          daysLeftLabel(trash.days_remaining, locale),
        )
      : t("In the trash", locale);
  return state === "active"
    ? t("Restored", locale)
    : t("Deleted permanently", locale);
}

/** "Owner: Anna" for the owner an administrator or the system acted on. */
export function lifecycleSubjectText(
  subject: LifecycleSubject | null,
  locale?: Locale,
): string | null {
  if (!subject) return null;
  return t("Owner: {name}", locale).replace(
    "{name}",
    subject.name ?? t("a deleted account", locale),
  );
}

/** Whether a resource card may link to the live resource: not while it sits in the trash. */
export function liveResourceHref(
  href: string | null,
  state: ResourceState | null,
  exists: boolean,
): string | null {
  return state === "in_trash" || !exists ? null : href;
}

/** Whole days until `purgeAt`, rounded up; 0 when due or unreadable. */
export function daysUntilPurge(
  purgeAt: string,
  now: Date = new Date(),
): number {
  const purge = Date.parse(purgeAt);
  if (Number.isNaN(purge)) return 0;
  return Math.max(0, Math.ceil((purge - now.valueOf()) / 86_400_000));
}
