import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { get } from "svelte/store";
import {
  clearFeedback,
  dismissFeedback,
  feedback,
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
});
