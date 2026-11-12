import type { NavIconName } from "#lib/components/NavIcon.svelte";
import type { BackupEntry, BackupKind, BackupList } from "./backups-api.ts";

/**
 * How the Backups page draws a backup: one badge per kind (a hue from the
 * design tokens plus an icon) and the rules for the Undo card and the day
 * groups. Android mirrors this in `BackupPresentation.kt`; keep the kinds,
 * tones, icons and labels in step.
 */

export type BackupTone =
  "nightly" | "update" | "migration" | "restore" | "manual";

export interface KindStyle {
  tone: BackupTone;
  icon: NavIconName;
}

const KIND_STYLES: Record<BackupKind, KindStyle> = {
  nightly: { tone: "nightly", icon: "moon" },
  predeploy: { tone: "update", icon: "upload" },
  pre_migrate: { tone: "migration", icon: "database" },
  pre_restore: { tone: "restore", icon: "reset" },
  manual: { tone: "manual", icon: "needle" },
};

export function kindStyle(kind: BackupKind): KindStyle {
  return KIND_STYLES[kind] ?? KIND_STYLES.manual;
}

/**
 * The backup the "Undo the last restore" card offers: the safety copy that the
 * last *successful* restore wrote, and only while that file still exists.
 * A stray `pre-restore-*` file, a failed restore (the data never changed) or a
 * restore that is still waiting for the restart do not count.
 */
export function undoCandidate(
  list: Pick<BackupList, "last_restore" | "pending_restore">,
  backups: readonly BackupEntry[],
): BackupEntry | null {
  const last = list.last_restore;
  if (!last || last.status !== "succeeded" || !last.pre_restore) return null;
  if (list.pending_restore) return null;
  return (
    backups.find(
      (entry) =>
        entry.name === last.pre_restore && entry.kind === "pre_restore",
    ) ?? null
  );
}

export type DayLabel =
  { kind: "today" } | { kind: "yesterday" } | { kind: "date"; date: Date };

export interface DayGroup {
  key: string;
  label: DayLabel;
  entries: BackupEntry[];
}

function dayKey(date: Date, timeZone?: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}

/**
 * Group backups (already newest first) by calendar day in `timeZone`
 * (default: the browser's). An entry without a valid time gets its own
 * undated group at the end of the list.
 */
export function groupByDay(
  backups: readonly BackupEntry[],
  now: Date,
  timeZone?: string,
): DayGroup[] {
  const today = dayKey(now, timeZone);
  const yesterday = dayKey(
    new Date(now.getTime() - 24 * 3600 * 1000),
    timeZone,
  );
  const groups: DayGroup[] = [];
  for (const entry of backups) {
    const date = new Date(entry.created_at);
    const valid = Number.isFinite(date.valueOf());
    const key = valid ? dayKey(date, timeZone) : "unknown";
    let group = groups.find((candidate) => candidate.key === key);
    if (!group) {
      const label: DayLabel =
        key === today
          ? { kind: "today" }
          : key === yesterday
            ? { kind: "yesterday" }
            : { kind: "date", date };
      group = { key, label, entries: [] };
      groups.push(group);
    }
    group.entries.push(entry);
  }
  return groups;
}

/** "12 minutes ago"; null once it is more than a month old. */
export function relativeAge(
  value: string,
  now: Date,
  locale: string,
): string | null {
  const then = new Date(value).valueOf();
  if (!Number.isFinite(then)) return null;
  const seconds = Math.round((now.valueOf() - then) / 1000);
  if (seconds < 0) return null;
  const format = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  if (seconds < 45) return format.format(0, "second");
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return format.format(-minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (hours < 24) return format.format(-hours, "hour");
  const days = Math.round(hours / 24);
  if (days < 31) return format.format(-days, "day");
  return null;
}
