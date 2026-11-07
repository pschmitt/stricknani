import { describe, expect, it } from "vitest";
import { viewerKeyTarget } from "./viewer-keys";

describe("viewerKeyTarget", () => {
  it("wraps with the arrow keys", () => {
    expect(viewerKeyTarget("ArrowRight", 1, 3)).toBe(2);
    expect(viewerKeyTarget("ArrowRight", 2, 3)).toBe(0);
    expect(viewerKeyTarget("ArrowLeft", 0, 3)).toBe(2);
    expect(viewerKeyTarget("ArrowLeft", 2, 3)).toBe(1);
  });

  it("jumps with Home and End", () => {
    expect(viewerKeyTarget("Home", 2, 5)).toBe(0);
    expect(viewerKeyTarget("End", 1, 5)).toBe(4);
  });

  it("ignores other keys, single photos and unknown positions", () => {
    expect(viewerKeyTarget("a", 0, 3)).toBeNull();
    expect(viewerKeyTarget("ArrowRight", 0, 1)).toBeNull();
    expect(viewerKeyTarget("ArrowRight", -1, 3)).toBeNull();
  });
});
