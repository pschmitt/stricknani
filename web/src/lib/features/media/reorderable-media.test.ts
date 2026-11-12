import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import ReorderableMedia from "./ReorderableMedia.svelte";

const item = (id: number) => ({
  id,
  thumbnail_url: `/media/thumbnails/${id}.jpg`,
  alt_text: `Photo ${id}`,
});
const props = (count: number, extra = {}) => ({
  items: Array.from({ length: count }, (_, i) => item(i + 1)),
  locale: "en" as const,
  label: "Photos",
  fallbackAlt: "Photo",
  onMove: async () => true,
  onDelete: () => {},
  ...extra,
});

describe("ReorderableMedia", () => {
  it("shows only the trash icon on a tile: no star, no drag grip", () => {
    const { body } = render(ReorderableMedia, {
      props: props(3, { firstIsCover: true }),
    });
    expect(body).toContain("delete-button");
    expect(body).not.toContain("grip");
    expect(body).not.toContain("make-cover");
    expect(body).not.toContain("Make cover");
    expect(body).not.toContain("Cover photo");
    expect(body.match(/<svg/g)?.length).toBe(3);
  });

  it("offers a keyboard entry, a hint and move actions for screen readers", () => {
    const { body } = render(ReorderableMedia, {
      props: props(3, { firstIsCover: true }),
    });
    expect(body).toContain("Reorder photos");
    expect(body).toContain("Drag to reorder. The first photo is the cover.");
    expect(body).toContain("Move photo 2 to first");
    expect(body).toContain('role="status"');
  });

  it("leaves the cover sentence out of a group without a cover", () => {
    const { body } = render(ReorderableMedia, { props: props(3) });
    expect(body).toContain("Drag to reorder.");
    expect(body).not.toContain("is the cover");
  });

  it("has no reorder controls for one photo or a read-only group", () => {
    expect(render(ReorderableMedia, { props: props(1) }).body).not.toContain(
      "Reorder photos",
    );
    expect(
      render(ReorderableMedia, { props: props(3, { reorderable: false }) })
        .body,
    ).not.toContain("Reorder photos");
  });
});
