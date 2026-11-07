import { describe, expect, it } from "vitest";
import {
  IDENTITY,
  MAX_SCALE,
  clampPan,
  clampScale,
  panBy,
  toggleZoom,
  wheelDeltaPixels,
  wheelZoomFactor,
  zoomAt,
  zoomKeyScale,
} from "./viewer-zoom";

const view = { width: 400, height: 300 };

describe("wheelZoomFactor", () => {
  it("zooms in when scrolling up and out when scrolling down", () => {
    expect(wheelZoomFactor(-100, 0)).toBeGreaterThan(1);
    expect(wheelZoomFactor(100, 0)).toBeLessThan(1);
    expect(wheelZoomFactor(0, 0)).toBe(1);
  });

  it("is proportional for small pinch deltas and bounded for big ones", () => {
    const small = wheelZoomFactor(-2, 0);
    expect(small).toBeGreaterThan(1);
    expect(small).toBeLessThan(wheelZoomFactor(-20, 0));
    expect(wheelZoomFactor(-5000, 0)).toBe(wheelZoomFactor(-120, 0));
  });

  it("treats lines and pages like pixels", () => {
    expect(wheelDeltaPixels(3, 1)).toBe(48);
    expect(wheelDeltaPixels(1, 2)).toBe(800);
    expect(wheelZoomFactor(-3, 1)).toBeCloseTo(wheelZoomFactor(-48, 0));
    expect(wheelZoomFactor(Number.NaN, 0)).toBe(1);
  });
});

describe("clampScale", () => {
  it("stays between 1x and the maximum", () => {
    expect(clampScale(0.2)).toBe(1);
    expect(clampScale(99)).toBe(MAX_SCALE);
    expect(clampScale(2.5)).toBe(2.5);
    expect(clampScale(Number.NaN)).toBe(1);
  });
});

describe("zoomAt", () => {
  it("keeps the point under the pointer fixed", () => {
    const anchor = { x: 100, y: -50 };
    const first = zoomAt(IDENTITY, 2, anchor, view, view);
    // content point under the anchor: (anchor - t) / scale
    const before = {
      x: (anchor.x - IDENTITY.x) / IDENTITY.scale,
      y: (anchor.y - IDENTITY.y) / IDENTITY.scale,
    };
    const after = {
      x: (anchor.x - first.x) / first.scale,
      y: (anchor.y - first.y) / first.scale,
    };
    expect(after.x).toBeCloseTo(before.x);
    expect(after.y).toBeCloseTo(before.y);
    const second = zoomAt(first, 3, anchor, view, view);
    expect((anchor.x - second.x) / second.scale).toBeCloseTo(before.x);
  });

  it("zooms around the centre without moving", () => {
    expect(zoomAt(IDENTITY, 3, { x: 0, y: 0 }, view, view)).toEqual({
      scale: 3,
      x: 0,
      y: 0,
    });
  });

  it("clamps the scale and resets the pan at 1x", () => {
    expect(zoomAt(IDENTITY, 50, { x: 0, y: 0 }, view, view).scale).toBe(
      MAX_SCALE,
    );
    const zoomed = zoomAt(IDENTITY, 2, { x: 150, y: 0 }, view, view);
    expect(zoomed.x).not.toBe(0);
    expect(zoomAt(zoomed, 1, { x: 0, y: 0 }, view, view)).toEqual(IDENTITY);
    expect(zoomAt(zoomed, 0.1, { x: 0, y: 0 }, view, view)).toEqual(IDENTITY);
  });

  it("keeps the zoomed photo covering the frame", () => {
    const corner = zoomAt(IDENTITY, 2, { x: 200, y: 150 }, view, view);
    expect(corner.x).toBe(-200);
    expect(corner.y).toBe(-150);
  });
});

describe("clampPan / panBy", () => {
  it("limits panning to the zoomed overflow", () => {
    const state = { scale: 2, x: 0, y: 0 };
    expect(clampPan({ ...state, x: 999, y: -999 }, view, view)).toEqual({
      scale: 2,
      x: 200,
      y: -150,
    });
    expect(panBy(state, 30, -20, view, view)).toEqual({
      scale: 2,
      x: 30,
      y: -20,
    });
    expect(panBy(state, 500, 0, view, view).x).toBe(200);
  });

  it("does not pan at fit and centres content smaller than the frame", () => {
    expect(panBy(IDENTITY, 40, 40, view, view)).toEqual(IDENTITY);
    const small = { width: 100, height: 100 };
    expect(clampPan({ scale: 2, x: 80, y: 80 }, small, view)).toEqual({
      scale: 2,
      x: 0,
      y: 0,
    });
  });
});

describe("toggleZoom", () => {
  it("jumps to 2x at the point and back to fit", () => {
    const zoomed = toggleZoom(IDENTITY, { x: 50, y: 0 }, view, view);
    expect(zoomed.scale).toBe(2);
    expect(zoomed.x).toBe(-50);
    expect(toggleZoom(zoomed, { x: 0, y: 0 }, view, view)).toEqual(IDENTITY);
  });
});

describe("zoomKeyScale", () => {
  it("steps in, out and resets", () => {
    expect(zoomKeyScale("+", 1)).toBeCloseTo(1.5);
    expect(zoomKeyScale("=", 1)).toBeCloseTo(1.5);
    expect(zoomKeyScale("-", 3)).toBeCloseTo(2);
    expect(zoomKeyScale("-", 1)).toBe(1);
    expect(zoomKeyScale("+", 5)).toBe(MAX_SCALE);
    expect(zoomKeyScale("0", 4)).toBe(1);
  });

  it("ignores other keys", () => {
    expect(zoomKeyScale("ArrowLeft", 2)).toBeNull();
    expect(zoomKeyScale("a", 2)).toBeNull();
  });
});
