import { ApiError, apiRequest } from "#lib/api.ts";
import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
import {
  actionLabel,
  fieldNoun,
  sharingHeadline,
} from "#lib/features/history/history-summary.ts";
import {
  activityActor,
  avatarPath,
  parseUserKind,
  type UserKind,
  type UserRef,
} from "#lib/user-label.ts";
import {
  isLifecycleAction,
  parseLifecycleSubject,
  parseResourceState,
  parseTrashStatus,
  trashedStateText,
  type LifecycleSubject,
  type ResourceState,
  type TrashStatus,
} from "#lib/features/trash/trash-state.ts";

export const ACTIVITY_PAGE_SIZE = 6;
/** Rows per page on the full activity page. */
export const ACTIVITY_FULL_PAGE_SIZE = 20;

export interface ActivityActor {
  /** Who it was, as this viewer may know it; null on an older server (read from `is_self`). */
  kind: UserKind | null;
  /** Display name, never an email; only for `self` and `user`. */
  name: string | null;
  is_self: boolean;
  avatar_url: string | null;
}

export interface ActivityItem {
  id: number;
  first_event_id: number;
  created_at: string;
  first_created_at: string;
  count: number;
  action: string;
  entity_type: "project" | "yarn";
  entity_id: number;
  entity_name: string | null;
  exists: boolean;
  href: string | null;
  thumbnail_url: string | null;
  actor: ActivityActor;
  changed_fields: string[];
  /** Where the item is now; absent on older servers. */
  state: ResourceState | null;
  /** Present only while the item is in the trash. */
  trash: TrashStatus | null;
  /** No account caused it: the purge job, a restore the server ran. */
  system: boolean;
  /** The owner an administrator or the system acted on, or the other account of a sharing event. */
  subject: LifecycleSubject | null;
  /** The role of a sharing event's subject; absent otherwise. */
  subject_role: string | null;
}

export interface ActivityPage {
  items: ActivityItem[];
  has_more: boolean;
  next_before: number | null;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalid(): ApiError {
  return new ApiError("Unexpected activity response", 200, "invalid_response");
}

function positive(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

/** Only same-origin paths are used as links or images. */
function localPath(value: unknown): string | null {
  return typeof value === "string" &&
    value.startsWith("/") &&
    !value.startsWith("//")
    ? value
    : null;
}

export function parseActivityItem(value: unknown): ActivityItem {
  if (
    !record(value) ||
    !positive(value.id) ||
    typeof value.created_at !== "string" ||
    typeof value.action !== "string" ||
    (value.entity_type !== "project" && value.entity_type !== "yarn") ||
    typeof value.entity_id !== "number" ||
    !record(value.actor)
  )
    throw invalid();
  const actor = value.actor;
  const fields = Array.isArray(value.changed_fields)
    ? value.changed_fields.filter((f): f is string => typeof f === "string")
    : [];
  const exists = value.exists === true;
  const state = parseResourceState(value.state, invalid);
  const trash = parseTrashStatus(value.trash, invalid);
  return {
    id: value.id,
    first_event_id: positive(value.first_event_id)
      ? value.first_event_id
      : value.id,
    created_at: value.created_at,
    first_created_at:
      typeof value.first_created_at === "string"
        ? value.first_created_at
        : value.created_at,
    count: positive(value.count) ? value.count : 1,
    action: value.action,
    entity_type: value.entity_type,
    entity_id: value.entity_id,
    entity_name:
      typeof value.entity_name === "string" && value.entity_name.trim() !== ""
        ? value.entity_name
        : null,
    exists,
    // An item in the trash has no page to open: the link would answer 404.
    href: exists && state !== "in_trash" ? localPath(value.href) : null,
    thumbnail_url: localPath(value.thumbnail_url),
    actor: {
      kind: parseUserKind(actor.kind),
      name:
        typeof actor.name === "string" && actor.name.trim() !== ""
          ? actor.name
          : null,
      is_self: actor.is_self === true,
      avatar_url: localPath(actor.avatar_url),
    },
    changed_fields: fields,
    state,
    trash,
    system: value.system === true,
    subject: parseLifecycleSubject(value.subject),
    subject_role:
      record(value.subject) && typeof value.subject.role === "string"
        ? value.subject.role
        : null,
  };
}

export function parseActivityPage(payload: unknown): ActivityPage {
  if (!record(payload) || !Array.isArray(payload.data)) throw invalid();
  const meta = payload.meta;
  if (!record(meta) || typeof meta.has_more !== "boolean") throw invalid();
  const next = meta.next_before;
  return {
    items: payload.data.map(parseActivityItem),
    has_more: meta.has_more && positive(next),
    next_before: positive(next) ? next : null,
  };
}

export async function loadActivity(
  signal: AbortSignal,
  before: number | null = null,
  limit = ACTIVITY_PAGE_SIZE,
): Promise<ActivityPage> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (before !== null) query.set("before", String(before));
  return parseActivityPage(
    await apiRequest(`/api/v2/activity?${query.toString()}`, { signal }),
  );
}

/** Appends a page, dropping items that an earlier page already showed. */
export function mergeActivity(
  current: ActivityItem[],
  next: ActivityItem[],
): ActivityItem[] {
  const seen = new Set(current.map((item) => item.id));
  return [...current, ...next.filter((item) => !seen.has(item.id))];
}

/** The actor of a feed item as a `UserRef`: You, a name, an administrator, System or Unknown. */
export function activityItemActor(item: ActivityItem): UserRef {
  return activityActor(item.actor, item.system);
}

/**
 * What happened, worded like the project history (`actionLabel`, sharing sentences): "Timer
 * started", "Restored from trash", "Shared with Anna · Can view". Android words it the same.
 */
export function activityHeadline(item: ActivityItem, locale: Locale): string {
  return (
    sharingHeadline(
      item.action,
      item.subject ? { ...item.subject, role: item.subject_role } : null,
      locale,
    ) ?? actionLabel(item.action, locale, item.system)
  );
}

/** The name to show for the resource: its name, else "a project" / "a yarn". */
export function activityResourceName(
  item: ActivityItem,
  locale: Locale,
): string {
  return (
    item.entity_name ??
    (item.entity_type === "project"
      ? t("a project", locale)
      : t("a yarn", locale))
  );
}

const extraNouns: Record<string, string> = {
  "project.image_count": msg("photos"),
  "yarn.photo_count": msg("photos"),
  "project.step_count": msg("steps"),
  "project.attachment_count": msg("attachments"),
};

const MAX_FIELDS = 3;

/** Muted detail line: "4 changes · name, needles". Empty when there is nothing to say. */
export function activityDetail(item: ActivityItem, locale: Locale): string {
  const parts: string[] = [];
  if (item.count > 1)
    parts.push(t("{n} changes", locale).replace("{n}", String(item.count)));
  const labels: string[] = [];
  for (const field of item.changed_fields) {
    const extra = extraNouns[field];
    const label = extra ? t(extra, locale) : fieldNoun(field, locale);
    if (label && !labels.includes(label)) labels.push(label);
  }
  if (labels.length > 0) {
    const shown =
      labels.length > MAX_FIELDS
        ? [
            ...labels.slice(0, MAX_FIELDS - 1),
            t("{n} more", locale).replace(
              "{n}",
              String(labels.length - (MAX_FIELDS - 1)),
            ),
          ]
        : labels;
    parts.push(shown.join(", "));
  }
  return parts.join(" · ");
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

function startOfDay(date: Date): number {
  return new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate(),
  ).valueOf();
}

/** "just now", "5 minutes ago", "2 hours ago", "yesterday", "3 days ago", "2 weeks ago", then a date. */
export function activityAgo(
  value: string,
  locale: Locale,
  now: Date = new Date(),
): string {
  const then = new Date(value);
  if (Number.isNaN(then.valueOf())) return "";
  const relative = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const diff = now.valueOf() - then.valueOf();
  if (diff < MINUTE) return relative.format(0, "second");
  if (diff < HOUR) return relative.format(-Math.floor(diff / MINUTE), "minute");
  const days = Math.round((startOfDay(then) - startOfDay(now)) / 86_400_000);
  if (days === 0) return relative.format(-Math.floor(diff / HOUR), "hour");
  if (days > -14) return relative.format(days, "day");
  if (days > -60) return relative.format(Math.round(days / 7), "week");
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(then);
}

/** Where a moved-to-trash item is now: "In the trash · 12 days left", "Restored"; else empty. */
export function activityStateNote(item: ActivityItem, locale: Locale): string {
  return trashedStateText(item.action, item.state, item.trash, locale) ?? "";
}

/** The owner of a trash, restore or purge entry, for an "Owner: {name}" hint; null otherwise. */
export function activityOwner(
  item: ActivityItem,
  locale: Locale,
): UserRef | null {
  if (!isLifecycleAction(item.action) || !item.subject) return null;
  return {
    kind: "user",
    name: item.subject.name ?? t("a deleted account", locale),
    avatarUrl: avatarPath(item.subject.avatar_url),
  };
}

/** Icon of a timeline node: what happened, not what it happened to. */
export type ActivityIcon =
  | "sparkle"
  | "trash"
  | "reset"
  | "action-update"
  | "camera"
  | "action-delete"
  | "action-remove"
  | "action-upload"
  | "stopwatch"
  | "share"
  | "action-default";

const exactIcons: Record<string, ActivityIcon> = {
  created: "sparkle",
  step_created: "sparkle",
  updated: "action-update",
  step_updated: "action-update",
  photo_promoted: "action-update",
  title_image_promoted: "action-update",
  uploaded: "camera",
  photo_uploaded: "camera",
  title_image_uploaded: "camera",
  stitch_sample_image_uploaded: "camera",
  step_image_uploaded: "camera",
  attachment_uploaded: "action-upload",
  deleted: "action-delete",
  trashed: "trash",
  restored: "reset",
  purged: "action-delete",
  step_deleted: "action-delete",
  attachment_deleted: "action-delete",
  image_deleted: "action-delete",
  photo_deleted: "action-delete",
  removed: "action-remove",
  timer_started: "stopwatch",
  timer_stopped: "stopwatch",
  timer_total_changed: "stopwatch",
  shared: "share",
  share_role_changed: "share",
  share_revoked: "action-remove",
  share_left: "action-remove",
  ownership_transferred: "share",
  step_trashed: "trash",
  step_restored: "reset",
  step_purged: "action-delete",
};

/** Maps an audit action to a node icon; unknown actions are read by their suffix. */
export function activityIcon(action: string): ActivityIcon {
  const key = action.toLowerCase();
  if (Object.hasOwn(exactIcons, key))
    return exactIcons[key] ?? "action-default";
  if (key.endsWith("_created")) return "sparkle";
  if (key.endsWith("_updated") || key.endsWith("_changed"))
    return "action-update";
  if (key.endsWith("_uploaded")) return "camera";
  if (key.endsWith("_deleted")) return "action-delete";
  if (key.endsWith("_removed")) return "action-remove";
  if (key.endsWith("_trashed")) return "trash";
  if (key.endsWith("_restored")) return "reset";
  if (key.endsWith("_purged")) return "action-delete";
  return "action-default";
}

export interface ActivityDay {
  /** Local calendar day, `YYYY-MM-DD`; `unknown` for an unreadable timestamp. */
  key: string;
  /** "Today", "Yesterday" or a short date; empty for `unknown`. */
  label: string;
  items: ActivityItem[];
}

const UNKNOWN_DAY = "unknown";

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** The local calendar day of a timestamp, or `unknown` when it cannot be read. */
export function activityDayKey(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return UNKNOWN_DAY;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** "Today", "Yesterday", else "Feb 14" (with the year when it is not this year). */
export function activityDayLabel(
  value: string,
  locale: Locale,
  now: Date = new Date(),
): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "";
  const days = Math.round((startOfDay(now) - startOfDay(date)) / 86_400_000);
  if (days <= 0) return t("Today", locale);
  if (days === 1) return t("Yesterday", locale);
  return new Intl.DateTimeFormat(locale, {
    month: "short",
    day: "numeric",
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }),
  }).format(date);
}

/** The time of day, 24-hour in both languages: "14:32". */
export function activityTime(value: string, locale: Locale): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "";
  return new Intl.DateTimeFormat(locale, {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(date);
}

function sameActor(a: ActivityItem, b: ActivityItem): boolean {
  return (
    a.actor.is_self === b.actor.is_self &&
    a.actor.kind === b.actor.kind &&
    a.actor.name === b.actor.name
  );
}

/** One-off facts (lifecycle, sharing, timer) are single events and never fold into a run. */
const standaloneActions = new Set([
  "created",
  "deleted",
  "trashed",
  "restored",
  "purged",
  "step_trashed",
  "step_restored",
  "step_purged",
  "shared",
  "share_role_changed",
  "share_revoked",
  "share_left",
  "ownership_transferred",
  "timer_started",
  "timer_stopped",
  "timer_total_changed",
]);

function mergeable(newer: ActivityItem, older: ActivityItem): boolean {
  return (
    newer.entity_type === older.entity_type &&
    newer.entity_id === older.entity_id &&
    newer.action === older.action &&
    !standaloneActions.has(newer.action) &&
    sameActor(newer, older)
  );
}

/**
 * Folds consecutive entries (newest first) for the same item, action and actor
 * into one: counts add up, the span widens and the changed fields are united.
 * The server already does this within 10 minutes; this extends it to the day.
 * Callers pass entries of a single day.
 */
export function mergeActivityRuns(items: ActivityItem[]): ActivityItem[] {
  const merged: ActivityItem[] = [];
  for (const item of items) {
    const last = merged[merged.length - 1];
    if (last && mergeable(last, item)) {
      merged[merged.length - 1] = {
        ...last,
        count: last.count + item.count,
        first_event_id: item.first_event_id,
        first_created_at: item.first_created_at,
        thumbnail_url: last.thumbnail_url ?? item.thumbnail_url,
        changed_fields: [
          ...last.changed_fields,
          ...item.changed_fields.filter(
            (f) => !last.changed_fields.includes(f),
          ),
        ],
      };
    } else {
      merged.push(item);
    }
  }
  return merged;
}

/** Groups a newest-first feed by local day and folds repeats within each day. */
export function groupActivity(
  items: ActivityItem[],
  locale: Locale,
  now: Date = new Date(),
): ActivityDay[] {
  const buckets: { key: string; label: string; items: ActivityItem[] }[] = [];
  for (const item of items) {
    const key = activityDayKey(item.created_at);
    const last = buckets[buckets.length - 1];
    if (last && last.key === key) last.items.push(item);
    else
      buckets.push({
        key,
        label: activityDayLabel(item.created_at, locale, now),
        items: [item],
      });
  }
  return buckets.map((b) => ({ ...b, items: mergeActivityRuns(b.items) }));
}
