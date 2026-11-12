import type { BackupCounts, BackupKind } from "./backups-api";

/** "1.4 MB": binary steps, one decimal from KB up. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** A count that the backup could not report reads as a dash. */
export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? "–" : String(value);
}

/** Sort key of the listing: newest first, the name breaks ties. */
export function compareBackups(
  a: { created_at: string; name: string },
  b: { created_at: string; name: string },
): number {
  const byTime = Date.parse(b.created_at) - Date.parse(a.created_at);
  return Number.isFinite(byTime) && byTime !== 0
    ? byTime
    : a.name.localeCompare(b.name);
}

export function countsDiffer(
  a: BackupCounts | null,
  b: BackupCounts | null,
  key: keyof BackupCounts,
): boolean {
  return (a?.[key] ?? null) !== (b?.[key] ?? null);
}

export const KIND_ORDER: readonly BackupKind[] = [
  "nightly",
  "predeploy",
  "pre_migrate",
  "pre_restore",
  "manual",
];
