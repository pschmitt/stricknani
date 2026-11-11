import { describe, expect, it } from "vitest";
import { daysLeftLabel } from "./trash-copy";
import type { TrashItem } from "./trash-api";
import {
  effectiveTrashScope,
  showsOwner,
  sortTrash,
  trashItemKey,
} from "./trash-view";

const item = (overrides: Partial<TrashItem>): TrashItem => ({
  kind: "project",
  id: 1,
  name: "Socks",
  owner: { id: 2, email: "a@example.test", name: "Anna", avatar_url: null },
  deleted_at: "2026-11-01T10:00:00Z",
  purge_at: "2027-01-30T10:00:00Z",
  days_remaining: 80,
  parent: null,
  detail: null,
  ...overrides,
});

describe("daysLeftLabel", () => {
  it("handles due, singular and plural", () => {
    expect(daysLeftLabel(0)).toBe("Due for deletion");
    expect(daysLeftLabel(-2)).toBe("Due for deletion");
    expect(daysLeftLabel(1)).toBe("1 day left");
    expect(daysLeftLabel(12)).toBe("12 days left");
  });
});

describe("trash view helpers", () => {
  it("lets only administrators choose a scope", () => {
    expect(effectiveTrashScope(true, "all")).toBe("all");
    expect(effectiveTrashScope(true, "mine")).toBe("mine");
    expect(effectiveTrashScope(false, "all")).toBe("mine");
  });

  it("shows the owner only in the everyone scope", () => {
    expect(showsOwner("all", item({}))).toBe(true);
    expect(showsOwner("mine", item({}))).toBe(false);
    expect(showsOwner("all", item({ owner: null }))).toBe(false);
  });

  it("sorts newest deletion first and keeps ties stable", () => {
    const sorted = sortTrash([
      item({ id: 1, deleted_at: "2026-11-01T10:00:00Z" }),
      item({ id: 2, deleted_at: "2026-11-03T10:00:00Z" }),
      item({ id: 3, deleted_at: "2026-11-03T10:00:00Z" }),
    ]);
    expect(sorted.map((entry) => entry.id)).toEqual([2, 3, 1]);
  });

  it("keys items by kind and id", () => {
    expect(trashItemKey({ kind: "step", id: 4 })).toBe("step:4");
    expect(trashItemKey({ kind: "image", id: 4 })).not.toBe(
      trashItemKey({ kind: "step", id: 4 }),
    );
  });
});
