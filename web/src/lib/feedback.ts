import { get, readonly, writable } from "svelte/store";

export type FeedbackKind = "success" | "error";

export interface FeedbackEvent {
  id: number;
  ownerId: number;
  kind: FeedbackKind;
  message: string;
}

const feedbackState = writable<FeedbackEvent | null>(null);
export const feedback = readonly(feedbackState);

/**
 * How long a message stays before it leaves on its own; null means until dismissed. Mirrors the
 * Android app: confirmations are short, only errors stay so they can not be missed.
 */
export function feedbackDurationMs(kind: FeedbackKind): number | null {
  return kind === "error" ? null : 4_000;
}

let nextFeedbackId = 0;
let successTimer: ReturnType<typeof setTimeout> | undefined;

export function publishFeedback(event: Omit<FeedbackEvent, "id">): number {
  if (successTimer !== undefined) clearTimeout(successTimer);

  const id = ++nextFeedbackId;
  feedbackState.set({ ...event, id });

  const duration = feedbackDurationMs(event.kind);
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
