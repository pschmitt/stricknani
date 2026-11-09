import { describe, expect, it } from "vitest";
import { groupProjectImages } from "./project-images";

const image = (id: number, step_id: number | null, is_title_image = false) => ({
  id,
  step_id,
  is_title_image,
});

describe("groupProjectImages", () => {
  it("keeps the cover and the other project photos in one gallery", () => {
    const images = [image(1, null, true), image(2, null), image(3, null)];
    const { gallery, orphans } = groupProjectImages(images, []);
    expect(gallery.map((entry) => entry.id)).toEqual([1, 2, 3]);
    expect(orphans).toEqual([]);
  });

  it("leaves step photos out and reports orphans", () => {
    const images = [image(1, null, true), image(2, 7), image(3, 99)];
    const { gallery, orphans } = groupProjectImages(images, [7]);
    expect(gallery.map((entry) => entry.id)).toEqual([1]);
    expect(orphans.map((entry) => entry.id)).toEqual([3]);
  });
});
