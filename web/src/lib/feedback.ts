import { get, readonly, writable } from "svelte/store";

export type FeedbackKind = "success" | "error";

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

let nextFeedbackId = 0;
let successTimer: ReturnType<typeof setTimeout> | undefined;

export function publishFeedback(event: Omit<FeedbackEvent, "id">): number {
  if (successTimer !== undefined) clearTimeout(successTimer);

  const id = ++nextFeedbackId;
  feedbackState.set({ ...event, id });

  if (event.kind === "success") {
    successTimer = setTimeout(
      () => dismissFeedback(id),
      event.action ? FEEDBACK_ACTION_DISMISS_MS : FEEDBACK_DISMISS_MS,
    );
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
