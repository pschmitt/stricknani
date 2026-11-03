import { describe, expect, it } from "vitest";
import { MAX_DRAWN_STITCHES, swatchPath } from "./knitted-swatch";

const vCount = (path: string) => (path.match(/M/g) ?? []).length;

describe("swatchPath", () => {
  it("draws one V per stitch, split into even and odd rows", () => {
    const swatch = swatchPath(4, "3")!;
    expect(vCount(swatch.even)).toBe(8);
    expect(vCount(swatch.odd)).toBe(4);
  });

  it("is empty until both counts are positive whole numbers", () => {
    expect(swatchPath(undefined, 30)).toBeNull();
    expect(swatchPath(22, "")).toBeNull();
    expect(swatchPath(0, 30)).toBeNull();
    expect(swatchPath("abc", 30)).toBeNull();
  });

  it("caps very dense gauges", () => {
    const swatch = swatchPath(500, 1)!;
    expect(vCount(swatch.even)).toBe(MAX_DRAWN_STITCHES);
  });
});
