// The "developer mode" easter egg on the About page: seven presses on the
// Commit row. It only reveals read-only diagnostics. The Android app has the
// same gag on its Build row.

export const REQUIRED_TAPS = 7;
export const SILENT_TAPS = 2;
export const TAP_WINDOW_MS = 2_000;
export const DEVELOPER_STORAGE_KEY = "stricknani.developerMode";

export type TapResult =
  | { kind: "silent" }
  | { kind: "already" }
  | { kind: "countdown"; stepsLeft: number }
  | { kind: "unlock" };

/** Counts presses; a pause of more than TAP_WINDOW_MS starts over. */
export class TapCounter {
  #count = 0;
  #lastAt = 0;

  tap(now: number, alreadyDeveloper: boolean): TapResult {
    if (alreadyDeveloper) return { kind: "already" };
    if (this.#count > 0 && now - this.#lastAt > TAP_WINDOW_MS) this.#count = 0;
    this.#lastAt = now;
    this.#count += 1;
    if (this.#count >= REQUIRED_TAPS) {
      this.#count = 0;
      return { kind: "unlock" };
    }
    if (this.#count <= SILENT_TAPS) return { kind: "silent" };
    return { kind: "countdown", stepsLeft: REQUIRED_TAPS - this.#count };
  }

  reset(): void {
    this.#count = 0;
  }
}

// One literal per count so the i18n extractor sees every source message.
const countdownMessages: Record<number, string> = {
  4: "You are now 4 steps away from being a developer.",
  3: "You are now 3 steps away from being a developer.",
  2: "You are now 2 steps away from being a developer.",
  1: "You are now 1 step away from being a developer.",
};

/** English source message for a result, or null when nothing is to be shown. */
export function tapMessage(result: TapResult): string | null {
  switch (result.kind) {
    case "silent":
      return null;
    case "already":
      return "No need, you are already a developer.";
    case "unlock":
      return "You are now a developer!";
    case "countdown":
      return countdownMessages[result.stepsLeft] ?? null;
  }
}

export function readDeveloperMode(): boolean {
  try {
    return (
      typeof localStorage !== "undefined" &&
      localStorage.getItem(DEVELOPER_STORAGE_KEY) === "1"
    );
  } catch {
    return false;
  }
}

export function writeDeveloperMode(enabled: boolean): void {
  try {
    if (typeof localStorage === "undefined") return;
    if (enabled) localStorage.setItem(DEVELOPER_STORAGE_KEY, "1");
    else localStorage.removeItem(DEVELOPER_STORAGE_KEY);
  } catch {
    // Storage unavailable: the mode lasts until the page is closed.
  }
}
