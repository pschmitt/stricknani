import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { get } from "svelte/store";
import {
  FEEDBACK_ACTION_DISMISS_MS,
  FEEDBACK_DISMISS_MS,
  clearFeedback,
  dismissFeedback,
  feedback,
  feedbackDurationMs,
  publishFeedback,
} from "./feedback";

describe("owner-scoped feedback events", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    clearFeedback();
  });

  afterEach(() => {
    clearFeedback();
    vi.useRealTimers();
  });

  it("does not let an old success timer dismiss a newer error", () => {
    publishFeedback({ ownerId: 7, kind: "success", message: "Saved" });
    vi.advanceTimersByTime(3_999);

    const errorId = publishFeedback({
      ownerId: 7,
      kind: "error",
      message: "Could not save",
    });
    vi.advanceTimersByTime(1);

    expect(get(feedback)).toMatchObject({
      id: errorId,
      ownerId: 7,
      kind: "error",
      message: "Could not save",
    });
  });

  it("ignores dismissal requests for an event that has been replaced", () => {
    const oldId = publishFeedback({
      ownerId: 7,
      kind: "error",
      message: "Old error",
    });
    const currentId = publishFeedback({
      ownerId: 9,
      kind: "error",
      message: "Current error",
    });

    dismissFeedback(oldId);

    expect(get(feedback)).toMatchObject({
      id: currentId,
      ownerId: 9,
      kind: "error",
      message: "Current error",
    });
  });

  it("clears the current event and its success timer", () => {
    publishFeedback({ ownerId: 7, kind: "success", message: "Saved" });

    clearFeedback();
    vi.advanceTimersByTime(4_000);

    expect(get(feedback)).toBeNull();
  });

  it("leaves confirmations on their own and keeps errors", () => {
    expect(feedbackDurationMs("success")).toBe(FEEDBACK_DISMISS_MS);
    expect(feedbackDurationMs("success", true)).toBe(
      FEEDBACK_ACTION_DISMISS_MS,
    );
    expect(feedbackDurationMs("error")).toBeNull();

    publishFeedback({
      ownerId: 7,
      kind: "success",
      message: "Project created",
    });
    vi.advanceTimersByTime(FEEDBACK_DISMISS_MS);
    expect(get(feedback)).toBeNull();

    publishFeedback({ ownerId: 7, kind: "error", message: "Could not save" });
    vi.advanceTimersByTime(600_000);
    expect(get(feedback)).not.toBeNull();
  });

  it("keeps a toast with an action longer than a plain one", () => {
    const onAction = vi.fn();
    const id = publishFeedback({
      ownerId: 7,
      kind: "success",
      message: "Moved to the trash",
      action: { label: "Undo", onAction },
    });

    vi.advanceTimersByTime(FEEDBACK_DISMISS_MS);
    expect(get(feedback)).toMatchObject({ id, action: { label: "Undo" } });

    vi.advanceTimersByTime(FEEDBACK_ACTION_DISMISS_MS - FEEDBACK_DISMISS_MS);
    expect(get(feedback)).toBeNull();
    expect(onAction).not.toHaveBeenCalled();
  });

  it("dismisses a plain success toast after the short delay", () => {
    publishFeedback({ ownerId: 7, kind: "success", message: "Saved" });
    vi.advanceTimersByTime(FEEDBACK_DISMISS_MS);
    expect(get(feedback)).toBeNull();
  });
});
