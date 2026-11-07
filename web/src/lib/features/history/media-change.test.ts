import { describe, expect, it } from "vitest";
import type { ChangeItemV1 } from "#lib/features/admin/admin-api.ts";
import {
  hasMediaThumbnails,
  markMediaLists,
  mediaLabels,
  parseChangeMedia,
} from "./media-change";

const order = (before: string[], after: string[]): ChangeItemV1 => ({
  field: "project.image_order",
  before: { type: "text_list", value: before },
  after: { type: "text_list", value: after },
});

describe("parseChangeMedia", () => {
  it("keeps same-origin entries and drops the rest", () => {
    const media = parseChangeMedia({
      "a.jpg": { url: "/media/p/1/a.jpg", thumbnail_url: "/media/t/1/a.jpg" },
      "b.jpg": { url: "https://evil.invalid/b.jpg", thumbnail_url: "/media/x" },
      "c.jpg": { url: "/media/../c", thumbnail_url: "/media/c" },
      "d.jpg": "nope",
    });
    expect(Object.keys(media ?? {})).toEqual(["a.jpg"]);
  });

  it("returns null when nothing is usable", () => {
    expect(parseChangeMedia(undefined)).toBeNull();
    expect(parseChangeMedia([])).toBeNull();
    expect(parseChangeMedia({})).toBeNull();
  });
});

describe("markMediaLists", () => {
  it("marks moved, added and removed pictures", () => {
    const marks = markMediaLists(["a", "b", "c", "x"], ["c", "a", "b", "y"]);
    expect(marks.before.map((m) => m.status)).toEqual([
      "same",
      "same",
      "moved",
      "removed",
    ]);
    expect(marks.after.map((m) => m.status)).toEqual([
      "moved",
      "same",
      "same",
      "added",
    ]);
  });

  it("treats identical lists as unchanged and handles empty sides", () => {
    expect(markMediaLists(["a"], ["a"]).after[0].status).toBe("same");
    expect(markMediaLists([], ["a"]).after[0].status).toBe("added");
    expect(markMediaLists(["a"], []).before[0].status).toBe("removed");
  });
});

describe("mediaLabels / hasMediaThumbnails", () => {
  it("reads lists and single labels of picture fields only", () => {
    const item = order(["a"], ["b"]);
    expect(mediaLabels(item, item.before)).toEqual(["a"]);
    const cover: ChangeItemV1 = {
      field: "project.cover_image",
      before: { type: "text", value: null },
      after: { type: "text", value: "b" },
    };
    expect(mediaLabels(cover, cover.before)).toEqual([]);
    expect(mediaLabels(cover, cover.after)).toEqual(["b"]);
    const name: ChangeItemV1 = {
      field: "project.name",
      before: { type: "text", value: "a" },
      after: { type: "text", value: "b" },
    };
    expect(mediaLabels(name, name.after)).toBeNull();
  });

  it("needs a thumbnail for at least one label", () => {
    const media = parseChangeMedia({
      a: { url: "/media/a", thumbnail_url: "/media/ta" },
    });
    expect(hasMediaThumbnails(order(["a"], ["b"]), media)).toBe(true);
    expect(hasMediaThumbnails(order(["z"], ["b"]), media)).toBe(false);
    expect(hasMediaThumbnails(order(["a"], ["b"]), null)).toBe(false);
  });
});
