import { describe, expect, it } from "vitest";
import {
  clamp01,
  ease,
  lerpRect,
  MIN_MORPH_DISTANCE,
  morphDistance,
  morphTransform,
  scrollProgress,
  slotProgress,
} from "./brand-handoff.svelte.ts";

describe("scrollProgress", () => {
  it("is 0 at the top and 1 once the distance is scrolled", () => {
    expect(scrollProgress(0, 100)).toBe(0);
    expect(scrollProgress(50, 100)).toBe(0.5);
    expect(scrollProgress(100, 100)).toBe(1);
    expect(scrollProgress(400, 100)).toBe(1);
  });

  it("clamps overscroll and handles a zero distance", () => {
    expect(scrollProgress(-30, 100)).toBe(0);
    expect(scrollProgress(0, 0)).toBe(1);
  });
});

describe("morphDistance", () => {
  it("runs until the hero reaches the slot", () => {
    expect(morphDistance(200, 12)).toBe(188);
  });

  it("never gets shorter than the minimum", () => {
    expect(morphDistance(20, 12)).toBe(MIN_MORPH_DISTANCE);
  });
});

describe("ease", () => {
  it("keeps the endpoints and the midpoint", () => {
    expect(ease(0)).toBe(0);
    expect(ease(1)).toBe(1);
    expect(ease(0.5)).toBe(0.5);
    expect(clamp01(Number.NaN)).toBe(0);
  });

  it("is monotonic", () => {
    let last = -1;
    for (let i = 0; i <= 20; i++) {
      const value = ease(i / 20);
      expect(value).toBeGreaterThanOrEqual(last);
      last = value;
    }
  });
});

describe("lerpRect / morphTransform", () => {
  const hero = { left: 20, top: 100, size: 88 };
  const slot = { left: 8, top: 10, size: 32 };

  it("starts at the hero and ends at the slot", () => {
    expect(lerpRect(hero, slot, 0)).toEqual(hero);
    expect(lerpRect(hero, slot, 1)).toEqual(slot);
    expect(lerpRect(hero, slot, 0.5)).toEqual({
      left: 14,
      top: 55,
      size: 60,
    });
  });

  it("scales relative to the laid-out size", () => {
    expect(morphTransform(hero, slot, 0, 88)).toEqual({
      x: 20,
      y: 100,
      scale: 1,
    });
    const end = morphTransform(hero, slot, 1, 88);
    expect(end.x).toBe(8);
    expect(end.y).toBe(10);
    expect(end.scale).toBeCloseTo(32 / 88);
  });
});

describe("slotProgress", () => {
  it("follows the eased progress with motion", () => {
    expect(slotProgress(0, false)).toBe(0);
    expect(slotProgress(0.5, false)).toBe(0.5);
    expect(slotProgress(1, false)).toBe(1);
  });

  it("steps with reduced motion", () => {
    expect(slotProgress(0, true)).toBe(0);
    expect(slotProgress(0.99, true)).toBe(0);
    expect(slotProgress(1, true)).toBe(1);
  });
});
