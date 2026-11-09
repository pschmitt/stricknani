/**
 * The import progress shown while the server reads a source. The server
 * reports no progress, so the steps follow typical timings (an AI import takes
 * about a minute, a plain one seconds) and the last step stays current until
 * the result arrives; the bar slows down and never fills before that.
 * Android's `ImportProgressPanel` uses the same steps and timings.
 */
export type ImportSourceKind = "url" | "text" | "file";

export type ImportStepId =
  "fetch" | "file" | "text" | "pictures" | "read" | "review";

export interface ImportStep {
  id: ImportStepId;
  /** Elapsed milliseconds from which the step is shown as current. */
  startsAt: number;
}

export function importSteps(kind: ImportSourceKind, ai: boolean): ImportStep[] {
  switch (kind) {
    case "url":
      return [
        { id: "fetch", startsAt: 0 },
        { id: "pictures", startsAt: ai ? 5_000 : 1_500 },
        { id: "read", startsAt: ai ? 12_000 : 3_000 },
        { id: "review", startsAt: ai ? 45_000 : 6_000 },
      ];
    case "file":
      return [
        { id: "file", startsAt: 0 },
        { id: "read", startsAt: ai ? 8_000 : 2_000 },
        { id: "review", startsAt: ai ? 40_000 : 5_000 },
      ];
    case "text":
      return [
        { id: "text", startsAt: 0 },
        { id: "read", startsAt: ai ? 3_000 : 1_000 },
        { id: "review", startsAt: ai ? 30_000 : 2_000 },
      ];
  }
}

/** The index of the current step after [elapsed] milliseconds. */
export function currentStep(steps: ImportStep[], elapsed: number): number {
  let index = 0;
  steps.forEach((step, i) => {
    if (elapsed >= step.startsAt) index = i;
  });
  return index;
}

/** The bar's fill (0..0.95): fast at first, then slower, never full before the end. */
export function progressFraction(elapsed: number, ai: boolean): number {
  const tau = ai ? 30_000 : 4_000;
  return 0.95 * (1 - Math.exp(-Math.max(0, elapsed) / tau));
}

/** "0:07", "1:23". */
export function formatElapsed(elapsed: number): string {
  const seconds = Math.floor(Math.max(0, elapsed) / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
