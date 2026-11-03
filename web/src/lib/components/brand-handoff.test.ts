import { describe, expect, it } from "vitest";
import {
  brandMarkShown,
  flipBetween,
  HERO_VISIBLE_THRESHOLD,
} from "./brand-handoff.svelte.ts";

describe("brandMarkShown", () => {
  it("is always shown on routes without a hero", () => {
    expect(brandMarkShown(false, 1)).toBe(true);
    expect(brandMarkShown(false, 0)).toBe(true);
  });

  it("hides while the hero is mostly visible", () => {
    expect(brandMarkShown(true, 1)).toBe(false);
    expect(brandMarkShown(true, 0.5)).toBe(false);
    expect(brandMarkShown(true, HERO_VISIBLE_THRESHOLD)).toBe(false);
  });

  it("shows once the hero is mostly out of view", () => {
    expect(brandMarkShown(true, 0.29)).toBe(true);
    expect(brandMarkShown(true, 0)).toBe(true);
  });
});

describe("flipBetween", () => {
  it("maps the target box onto the source box", () => {
    const flip = flipBetween(
      { left: 20, top: 100, size: 88 },
      { left: 8, top: 10, size: 32 },
    );
    expect(flip).toEqual({ dx: 12, dy: 90, scale: 2.75 });
  });
});
