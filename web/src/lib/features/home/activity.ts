import { ApiError, apiRequest } from "#lib/api.ts";
import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
import { fieldNoun } from "#lib/features/history/history-summary.ts";

export const ACTIVITY_PAGE_SIZE = 6;

export interface ActivityActor {
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
    href: exists ? localPath(value.href) : null,
    thumbnail_url: localPath(value.thumbnail_url),
    actor: {
      name: typeof actor.name === "string" ? actor.name : null,
      is_self: actor.is_self === true,
      avatar_url: localPath(actor.avatar_url),
    },
    changed_fields: fields,
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

export interface ActivitySentence {
  before: string;
  /** The resource name, rendered in bold; null when the name is not known. */
  name: string | null;
  after: string;
}

const NAME = "{name}";

const selfSentences: Record<string, string> = {
  created: msg("You created {name}"),
  updated: msg("You updated {name}"),
  deleted: msg("You deleted {name}"),
  uploaded: msg("You added files to {name}"),
  removed: msg("You removed files from {name}"),
};
const otherSentences: Record<string, string> = {
  created: msg("An admin created {name}"),
  updated: msg("An admin updated {name}"),
  deleted: msg("An admin deleted {name}"),
  uploaded: msg("An admin added files to {name}"),
  removed: msg("An admin removed files from {name}"),
};
const selfFallback = msg("You changed {name}");
const otherFallback = msg("An admin changed {name}");

/** "You updated **Winter socks**" as three parts, so the name can be emphasised. */
export function activitySentence(
  item: ActivityItem,
  locale: Locale,
): ActivitySentence {
  const table = item.actor.is_self ? selfSentences : otherSentences;
  const fallback = item.actor.is_self ? selfFallback : otherFallback;
  const template = t(table[item.action] ?? fallback, locale);
  const known = item.entity_name !== null;
  const name = known
    ? item.entity_name
    : item.entity_type === "project"
      ? t("a project", locale)
      : t("a yarn", locale);
  const at = template.indexOf(NAME);
  if (at < 0) return { before: template, name: null, after: "" };
  return {
    before: template.slice(0, at),
    name: known ? name : null,
    after: known
      ? template.slice(at + NAME.length)
      : `${name}${template.slice(at + NAME.length)}`,
  };
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
