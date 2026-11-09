import type { HistorySubject } from "./history-api";
import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
import type {
  ChangeItemV1,
  ChangeSetV1,
} from "#lib/features/admin/admin-api.ts";
import {
  auditActionPresentation,
  type AuditActionIcon,
} from "#lib/features/admin/audit-action.ts";

/** Colour family of an action, matching the audit log badges. */
export type HistoryTone = "create" | "update" | "delete" | "upload" | "other";

export function actionTone(action: string): HistoryTone {
  const presentation = auditActionPresentation(action);
  const icon: AuditActionIcon | "action-default" = presentation.icon;
  switch (icon) {
    case "action-create":
      return "create";
    case "action-update":
      return "update";
    case "action-delete":
    case "action-remove":
      return "delete";
    case "action-upload":
      return "upload";
    default:
      return "other";
  }
}

export function actionLabel(action: string, locale: Locale): string {
  const presentation = auditActionPresentation(action);
  return presentation.known
    ? t(presentation.messageKey, locale)
    : presentation.label;
}

/** Short noun phrases ("name", "notes") for the fields a change set can touch. */
const fieldNouns: Record<string, string> = {
  "project.name": msg("name"),
  "project.category": msg("category"),
  "project.yarn": msg("yarn"),
  "project.yarns": msg("linked yarns"),
  "project.needles": msg("needles"),
  "project.stitch_sample": msg("stitch sample"),
  "project.description": msg("description"),
  "project.notes": msg("notes"),
  "project.other_materials": msg("other materials"),
  "project.tags": msg("tags"),
  "project.steps": msg("steps"),
  "project.is_ai_enhanced": msg("AI enhancement"),
  "project.link": msg("source link"),
  "project.cover_image": msg("cover picture"),
  "project.image_order": msg("picture order"),
  "project.step_image_order": msg("step picture order"),
  "yarn.primary_photo": msg("main photo"),
  "yarn.photo_order": msg("photo order"),
  "yarn.name": msg("name"),
  "yarn.description": msg("description"),
  "yarn.brand": msg("brand"),
  "yarn.colorway": msg("colorway"),
  "yarn.dye_lot": msg("dye lot"),
  "yarn.fiber_content": msg("fiber content"),
  "yarn.weight_category": msg("yarn weight"),
  "yarn.recommended_needles": msg("recommended needles"),
  "yarn.weight_grams": msg("skein weight"),
  "yarn.length_meters": msg("skein length"),
  "yarn.notes": msg("notes"),
  "yarn.is_ai_enhanced": msg("AI enhancement"),
  "yarn.link": msg("source link"),
  "category.name": msg("name"),
};

/** The short noun for a change-field key ("name"), or null for a field without one. */
export function fieldNoun(field: string, locale: Locale): string | null {
  const noun = fieldNouns[field];
  return noun ? t(noun, locale) : null;
}

type CountKind = "photo" | "step" | "attachment";

const countFields: Record<string, CountKind> = {
  "project.image_count": "photo",
  "yarn.photo_count": "photo",
  "project.step_count": "step",
  "project.attachment_count": "attachment",
};

function countDelta(item: ChangeItemV1): number | null {
  if (item.before.type !== "integer" || item.after.type !== "integer")
    return null;
  return (item.after.value ?? 0) - (item.before.value ?? 0);
}

function countPhrase(kind: CountKind, change: number, locale: Locale): string {
  const n = Math.abs(change);
  const adding = change > 0;
  if (kind === "photo") {
    if (n === 1)
      return adding ? t("Added a photo", locale) : t("Removed a photo", locale);
    return (
      adding ? t("Added {n} photos", locale) : t("Removed {n} photos", locale)
    ).replace("{n}", String(n));
  }
  if (kind === "step") {
    if (n === 1)
      return adding ? t("Added a step", locale) : t("Removed a step", locale);
    return (
      adding ? t("Added {n} steps", locale) : t("Removed {n} steps", locale)
    ).replace("{n}", String(n));
  }
  if (n === 1)
    return adding
      ? t("Added an attachment", locale)
      : t("Removed an attachment", locale);
  return (
    adding
      ? t("Added {n} attachments", locale)
      : t("Removed {n} attachments", locale)
  ).replace("{n}", String(n));
}

function joinList(items: string[], locale: Locale): string {
  try {
    return new Intl.ListFormat(locale, {
      style: "long",
      type: "conjunction",
    }).format(items);
  } catch {
    return items.join(", ");
  }
}

/**
 * A human summary of what a change set did, e.g. "Changed name and notes" or "Added 2 photos".
 * Returns null when the change set has nothing summarisable (the caller falls back to the action).
 */
export function summarizeChanges(
  changes: ChangeSetV1 | null,
  locale: Locale,
): string | null {
  if (!changes || changes.items.length === 0) return null;
  const phrases: string[] = [];
  const countedKinds = new Set<CountKind>();
  const touched: string[] = [];
  for (const item of changes.items) {
    const kind = countFields[item.field];
    if (kind) {
      const change = countDelta(item);
      if (change) {
        phrases.push(countPhrase(kind, change, locale));
        countedKinds.add(kind);
      }
    }
  }
  for (const item of changes.items) {
    if (countFields[item.field]) continue;
    if (item.field === "project.steps" && countedKinds.has("step")) continue;
    const noun = fieldNouns[item.field];
    if (noun) {
      const label = t(noun, locale);
      if (!touched.includes(label)) touched.push(label);
    }
  }
  if (touched.length > 0) {
    const shown =
      touched.length > 3
        ? [
            ...touched.slice(0, 2),
            t("{n} more", locale).replace("{n}", String(touched.length - 2)),
          ]
        : touched;
    phrases.unshift(
      t("Changed {fields}", locale).replace(
        "{fields}",
        joinList(shown, locale),
      ),
    );
  }
  return phrases.length ? phrases.join(" · ") : null;
}

/**
 * The sentence for a sharing event with its person ("Transferred to Anika"), or null for any
 * other action. Android words it the same (`sharingEventLine`).
 */
export function sharingHeadline(
  action: string,
  subject: HistorySubject | null | undefined,
  locale: Locale,
): string | null {
  const name = subject?.name?.trim() || t("a deleted account", locale);
  const role =
    subject?.role === "editor" ? t("Can edit", locale) : t("Can view", locale);
  let text: string;
  switch (action) {
    case "shared":
      text = t("Shared with {name} · {role}", locale);
      break;
    case "share_role_changed":
      text = t("Role of {name} changed to {role}", locale);
      break;
    case "share_revoked":
      text = t("Stopped sharing with {name}", locale);
      break;
    case "share_left":
      text = t("{name} left", locale);
      break;
    case "ownership_transferred":
      text = t("Transferred to {name}", locale);
      break;
    default:
      return null;
  }
  return text.replace("{name}", name).replace("{role}", role);
}

/** The headline of a history row: a sharing sentence, the change summary, else the action name. */
export function eventHeadline(
  action: string,
  changes: ChangeSetV1 | null,
  locale: Locale,
  subject?: HistorySubject | null,
): string {
  return (
    sharingHeadline(action, subject, locale) ??
    summarizeChanges(changes, locale) ??
    actionLabel(action, locale)
  );
}

const units: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 365 * 24 * 3600],
  ["month", 30 * 24 * 3600],
  ["week", 7 * 24 * 3600],
  ["day", 24 * 3600],
  ["hour", 3600],
  ["minute", 60],
];

/** "2 hours ago" / "vor 2 Stunden"; falls back to the raw text for an unparsable date. */
export function relativeTime(iso: string, now: Date, locale: Locale): string {
  const then = new Date(iso);
  if (Number.isNaN(then.valueOf())) return iso;
  const seconds = Math.round((then.getTime() - now.getTime()) / 1000);
  const formatter = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size)
      return formatter.format(Math.trunc(seconds / size), unit);
  }
  return formatter.format(0, "second");
}

export function fullDate(iso: string, locale: Locale): string {
  const date = new Date(iso);
  return Number.isNaN(date.valueOf())
    ? iso
    : new Intl.DateTimeFormat(locale, {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(date);
}

/** What the details of a history event can show: its file, recorded field changes, or nothing. */
export type HistoryDetailKind = "media" | "changes" | "none";

export function historyDetailKind(event: {
  has_changes: boolean;
  media: unknown | null;
}): HistoryDetailKind {
  if (event.media) return "media";
  return event.has_changes ? "changes" : "none";
}
