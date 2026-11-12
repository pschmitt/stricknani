import { describe, expect, it } from "vitest";
import {
  daysUntilPurge,
  isLifecycleAction,
  lifecycleSubjectText,
  liveResourceHref,
  parseLifecycleSubject,
  parseResourceState,
  parseTrashStatus,
  trashedStateText,
} from "./trash-state";

const fail = () => new Error("invalid");
const status = {
  deleted_at: "2026-11-01T10:00:00Z",
  purge_at: "2027-01-30T10:00:00Z",
  days_remaining: 12,
  can_restore: true,
};

describe("lifecycle parsing", () => {
  it("accepts the three states and treats absence as unknown", () => {
    expect(parseResourceState("in_trash", fail)).toBe("in_trash");
    expect(parseResourceState("active", fail)).toBe("active");
    expect(parseResourceState("gone", fail)).toBe("gone");
    expect(parseResourceState(undefined, fail)).toBeNull();
    expect(() => parseResourceState("archived", fail)).toThrow("invalid");
  });

  it("parses the trash block and rejects a broken one", () => {
    expect(parseTrashStatus(status, fail)).toEqual(status);
    expect(parseTrashStatus(undefined, fail)).toBeNull();
    expect(() =>
      parseTrashStatus({ ...status, days_remaining: -1 }, fail),
    ).toThrow("invalid");
    expect(() =>
      parseTrashStatus({ ...status, can_restore: "yes" }, fail),
    ).toThrow("invalid");
  });

  it("reads the subject leniently", () => {
    expect(
      parseLifecycleSubject({ id: 3, name: " Anna ", role: null }),
    ).toEqual({ id: 3, name: "Anna", avatar_url: null });
    expect(
      parseLifecycleSubject({ id: 3, name: "Anna", avatar_url: "/a.png" })
        ?.avatar_url,
    ).toBe("/a.png");
    expect(parseLifecycleSubject({ id: null, name: null })).toEqual({
      id: null,
      name: null,
      avatar_url: null,
    });
    expect(parseLifecycleSubject("x")).toBeNull();
  });
});

describe("lifecycle text", () => {
  it("knows the trash actions", () => {
    expect(["trashed", "restored", "purged"].every(isLifecycleAction)).toBe(
      true,
    );
    expect(isLifecycleAction("deleted")).toBe(false);
  });

  it("describes where a trashed item is now", () => {
    const parse = (days: number) =>
      parseTrashStatus({ ...status, days_remaining: days }, fail);
    expect(trashedStateText("trashed", "in_trash", parse(12))).toBe(
      "In the trash · 12 days left",
    );
    expect(trashedStateText("trashed", "in_trash", parse(1))).toBe(
      "In the trash · 1 day left",
    );
    expect(trashedStateText("trashed", "in_trash", parse(0))).toBe(
      "In the trash · Due for deletion",
    );
    expect(trashedStateText("trashed", "in_trash", null)).toBe("In the trash");
    expect(trashedStateText("trashed", "active", null)).toBe("Restored");
    expect(trashedStateText("trashed", "gone", null)).toBe(
      "Deleted permanently",
    );
  });

  it("says nothing for other actions or an unknown state", () => {
    expect(trashedStateText("restored", "active", null)).toBeNull();
    expect(trashedStateText("trashed", null, null)).toBeNull();
  });

  it("names the owner an administrator acted on", () => {
    expect(lifecycleSubjectText({ id: 2, name: "Anna" })).toBe("Owner: Anna");
    expect(lifecycleSubjectText({ id: null, name: null })).toBe(
      "Owner: a deleted account",
    );
    expect(lifecycleSubjectText(null)).toBeNull();
  });

  it("never links the live resource while it is in the trash", () => {
    expect(liveResourceHref("/app/projects/3", "in_trash", false)).toBeNull();
    expect(liveResourceHref("/app/projects/3", "active", false)).toBeNull();
    expect(liveResourceHref("/app/projects/3", "active", true)).toBe(
      "/app/projects/3",
    );
    expect(liveResourceHref("/app/projects/3", null, true)).toBe(
      "/app/projects/3",
    );
  });
});

describe("trashed project or yarn fields", () => {
  it("counts the days until the purge, rounded up", () => {
    const now = new Date("2026-11-01T10:00:00Z");
    expect(daysUntilPurge("2026-11-04T09:00:00Z", now)).toBe(3);
    expect(daysUntilPurge("2026-11-04T10:00:00Z", now)).toBe(3);
    expect(daysUntilPurge("2026-10-30T10:00:00Z", now)).toBe(0);
    expect(daysUntilPurge("nope", now)).toBe(0);
  });
});
