import { get, readonly, writable } from "svelte/store";

/**
 * What a toast is about. Besides plain confirmations and errors there are kinds for what happened
 * to something: sent to the trash or removed ("deleted"), deleted for good ("purged") and brought
 * back ("restored"). Mirrors the Android app's feedback kinds.
 */
export type FeedbackKind =
  "success" | "error" | "deleted" | "purged" | "restored";

export type FeedbackIconName = "check" | "exclamation" | "trash" | "history";

/** The badge glyph of a kind: one shared icon per kind, as on Android. */
export function feedbackIconName(kind: FeedbackKind): FeedbackIconName {
  switch (kind) {
    case "success":
      return "check";
    case "error":
      return "exclamation";
    case "deleted":
    case "purged":
      return "trash";
    case "restored":
      return "history";
  }
}

/** An optional button on a toast, such as "Undo". Dismisses the toast when it runs. */
export interface FeedbackAction {
  label: string;
  onAction: () => void | Promise<void>;
}

export interface FeedbackEvent {
  id: number;
  ownerId: number;
  kind: FeedbackKind;
  message: string;
  action?: FeedbackAction;
}

/** How long a success toast stays; longer with an action so it can be reached. */
export const FEEDBACK_DISMISS_MS = 4_000;
export const FEEDBACK_ACTION_DISMISS_MS = 8_000;

const feedbackState = writable<FeedbackEvent | null>(null);
export const feedback = readonly(feedbackState);

/**
 * How long a message stays before it leaves on its own; null means until dismissed. Mirrors the
 * Android app: confirmations are short, only errors stay so they can not be missed.
 */
export function feedbackDurationMs(
  kind: FeedbackKind,
  hasAction = false,
): number | null {
  if (kind === "error") return null;
  return hasAction ? FEEDBACK_ACTION_DISMISS_MS : FEEDBACK_DISMISS_MS;
}

let nextFeedbackId = 0;
let successTimer: ReturnType<typeof setTimeout> | undefined;

export function publishFeedback(event: Omit<FeedbackEvent, "id">): number {
  if (successTimer !== undefined) clearTimeout(successTimer);

  const id = ++nextFeedbackId;
  feedbackState.set({ ...event, id });

  const duration = feedbackDurationMs(event.kind, event.action !== undefined);
  successTimer =
    duration === null
      ? undefined
      : setTimeout(() => dismissFeedback(id), duration);

  return id;
}

export function dismissFeedback(id: number): void {
  if (get(feedbackState)?.id !== id) return;
  if (successTimer !== undefined) clearTimeout(successTimer);
  successTimer = undefined;
  feedbackState.set(null);
}

export function clearFeedback(): void {
  if (successTimer !== undefined) clearTimeout(successTimer);
  successTimer = undefined;
  feedbackState.set(null);
}
