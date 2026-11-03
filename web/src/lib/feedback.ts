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

let nextFeedbackId = 0;
let successTimer: ReturnType<typeof setTimeout> | undefined;

export function publishFeedback(event: Omit<FeedbackEvent, "id">): number {
  if (successTimer !== undefined) clearTimeout(successTimer);

  const id = ++nextFeedbackId;
  feedbackState.set({ ...event, id });

  if (event.kind === "success") {
    successTimer = setTimeout(() => dismissFeedback(id), 4_000);
  } else {
    successTimer = undefined;
  }

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
