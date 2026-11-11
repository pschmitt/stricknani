import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { get } from "svelte/store";
import { ApiError } from "#lib/api.ts";
import { clearFeedback, feedback } from "#lib/feedback.ts";
import {
  publishMovedToTrash,
  publishSavedWithRemovals,
  restoreErrorMessage,
} from "./trash-undo";

const restore = vi.hoisted(() => vi.fn());
vi.mock("./trash-api", () => ({ restoreTrashItem: restore }));

const session = { csrf_token: "csrf", locale: "en" } as never;

beforeEach(() => {
  vi.useFakeTimers();
  clearFeedback();
  restore.mockReset();
});

afterEach(() => {
  clearFeedback();
  vi.useRealTimers();
});

describe("publishMovedToTrash", () => {
  it("offers Undo that restores and reports success", async () => {
    restore.mockResolvedValue(undefined);
    const onRestored = vi.fn();
    publishMovedToTrash({
      session,
      ownerId: 4,
      kind: "project",
      id: 9,
      onRestored,
    });
    const event = get(feedback);
    expect(event).toMatchObject({
      kind: "success",
      message: "Moved to the trash.",
      action: { label: "Undo" },
    });

    await event?.action?.onAction();

    expect(restore).toHaveBeenCalledWith("project", 9, session);
    expect(onRestored).toHaveBeenCalledOnce();
    expect(get(feedback)).toMatchObject({
      kind: "success",
      message: "Restored from the trash.",
    });
  });

  it("shows an error and skips the follow-up when the restore fails", async () => {
    restore.mockRejectedValue(
      new ApiError("Restore first", 409, "parent_in_trash"),
    );
    const onRestored = vi.fn();
    publishMovedToTrash({
      session,
      ownerId: 4,
      kind: "step",
      id: 1,
      onRestored,
    });

    await get(feedback)?.action?.onAction();

    expect(onRestored).not.toHaveBeenCalled();
    expect(get(feedback)).toMatchObject({
      kind: "error",
      message: "Restore first",
    });
  });
});

describe("publishSavedWithRemovals", () => {
  const items = [
    { kind: "step" as const, id: 11 },
    { kind: "image" as const, id: 5 },
  ];

  it("restores exactly the removed items in order, then refreshes", async () => {
    restore.mockResolvedValue(undefined);
    const onRestored = vi.fn();
    publishSavedWithRemovals({ session, ownerId: 4, items, onRestored });
    const event = get(feedback);
    expect(event).toMatchObject({
      kind: "success",
      message: "Saved. What you removed is in the trash for 90 days.",
      action: { label: "Undo" },
    });

    await event?.action?.onAction();

    expect(restore.mock.calls).toEqual([
      ["step", 11, session],
      ["image", 5, session],
    ]);
    expect(onRestored).toHaveBeenCalledOnce();
    expect(get(feedback)).toMatchObject({
      message: "Restored from the trash.",
    });
  });

  it("stops at the first failure and reports it", async () => {
    restore.mockRejectedValueOnce(new ApiError("x", 404));
    const onRestored = vi.fn();
    publishSavedWithRemovals({ session, ownerId: 4, items, onRestored });

    await get(feedback)?.action?.onAction();

    expect(restore).toHaveBeenCalledTimes(1);
    expect(onRestored).not.toHaveBeenCalled();
    expect(get(feedback)).toMatchObject({
      kind: "error",
      message: "This item is no longer in the trash.",
    });
  });
});

describe("restoreErrorMessage", () => {
  it("explains a missing item and falls back for unknown errors", () => {
    expect(restoreErrorMessage(new ApiError("x", 404))).toBe(
      "This item is no longer in the trash.",
    );
    expect(restoreErrorMessage(new Error("boom"))).toBe(
      "The item could not be restored.",
    );
  });
});
