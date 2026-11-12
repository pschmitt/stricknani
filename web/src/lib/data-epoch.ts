/**
 * The server's data epoch changes when an administrator restores a backup.
 * A browser that remembers another epoch drops what it kept about the old data
 * (timer reminders and library scopes name project and yarn ids that may now
 * mean something else) and reloads. Changes made on this device that had not
 * reached the server before the restore are lost with the old data.
 */

export const DATA_EPOCH_KEY = "stricknani.data-epoch";

/** Local keys that describe server data and must not survive a restore. */
export const DATA_BOUND_PREFIXES = [
  "stricknani:timer-reminder:",
  "stricknani.projectTimers.",
  "stricknani.library-scope.",
  "stricknani.pending-reauth",
] as const;

export type EpochCheck = "first" | "unchanged" | "changed";

export interface EpochStorage {
  readonly length: number;
  key(index: number): string | null;
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function compareEpoch(
  stored: string | null,
  current: string | null,
): EpochCheck {
  if (current === null || current === "") return "unchanged";
  if (stored === null) return "first";
  return stored === current ? "unchanged" : "changed";
}

export function discardDataBoundState(storage: EpochStorage): number {
  const doomed: string[] = [];
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index);
    if (key && DATA_BOUND_PREFIXES.some((prefix) => key.startsWith(prefix)))
      doomed.push(key);
  }
  for (const key of doomed) storage.removeItem(key);
  return doomed.length;
}

/**
 * Remember `current` and say what changed. On "changed" the data-bound local
 * state is already discarded; the caller reloads and tells the user.
 */
export function applyDataEpoch(
  storage: EpochStorage | undefined,
  current: string | null,
): EpochCheck {
  if (!storage) return "unchanged";
  try {
    const result = compareEpoch(storage.getItem(DATA_EPOCH_KEY), current);
    if (result === "changed") discardDataBoundState(storage);
    if (result !== "unchanged" && current !== null)
      storage.setItem(DATA_EPOCH_KEY, current);
    return result;
  } catch {
    return "unchanged";
  }
}
