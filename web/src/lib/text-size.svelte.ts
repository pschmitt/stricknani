// Reader text size shared by the project reader and the yarn detail.
// Persisted per viewer in localStorage; every storage access is guarded so
// private mode, blocked storage and SSR fall back to 100%.

export const TEXT_SIZE_STEPS = [90, 100, 115] as const;
export const TEXT_SIZE_STORAGE_KEY = "stricknani.textSize";

const DEFAULT_INDEX = 1;
const CLASSES = ["reading-small", "reading-medium", "reading-large"] as const;

function indexFromStored(raw: string | null): number {
  const index = TEXT_SIZE_STEPS.findIndex((step) => String(step) === raw);
  return index === -1 ? DEFAULT_INDEX : index;
}

function readStored(): number {
  try {
    if (typeof localStorage === "undefined") return DEFAULT_INDEX;
    return indexFromStored(localStorage.getItem(TEXT_SIZE_STORAGE_KEY));
  } catch {
    return DEFAULT_INDEX;
  }
}

function writeStored(index: number): void {
  try {
    if (typeof localStorage === "undefined") return;
    if (index === DEFAULT_INDEX) {
      localStorage.removeItem(TEXT_SIZE_STORAGE_KEY);
    } else {
      localStorage.setItem(
        TEXT_SIZE_STORAGE_KEY,
        String(TEXT_SIZE_STEPS[index]),
      );
    }
  } catch {
    // Storage unavailable: keep the in-memory value only.
  }
}

function clamp(index: number): number {
  if (!Number.isInteger(index)) return DEFAULT_INDEX;
  return Math.min(TEXT_SIZE_STEPS.length - 1, Math.max(0, index));
}

let index = $state(DEFAULT_INDEX);

export const textSize = {
  /** Current step index (0..2). */
  get index(): number {
    return index;
  },
  get percent(): number {
    return TEXT_SIZE_STEPS[index];
  },
  get label(): string {
    return `${TEXT_SIZE_STEPS[index]}%`;
  },
  get cssClass(): string {
    return CLASSES[index];
  },
  get isMin(): boolean {
    return index === 0;
  },
  get isMax(): boolean {
    return index === TEXT_SIZE_STEPS.length - 1;
  },
  get isDefault(): boolean {
    return index === DEFAULT_INDEX;
  },
  set(next: number): void {
    index = clamp(next);
    writeStored(index);
  },
  up(): void {
    textSize.set(index + 1);
  },
  down(): void {
    textSize.set(index - 1);
  },
  reset(): void {
    textSize.set(DEFAULT_INDEX);
  },
  /** Re-read the persisted value (used at startup and in tests). */
  reload(): void {
    index = readStored();
  },
};

if (typeof window !== "undefined") {
  textSize.reload();
  try {
    window.addEventListener("storage", (event) => {
      if (event.key === null || event.key === TEXT_SIZE_STORAGE_KEY) {
        index = indexFromStored(event.newValue);
      }
    });
  } catch {
    // No event support: cross-tab sync is optional.
  }
}
