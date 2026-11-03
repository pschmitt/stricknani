import { describe, expect, it } from "vitest";
import {
  STORAGE_KEY,
  bandHeight,
  clampToViewport,
  createFabDrag,
  createTiltTracker,
  defaultPlacement,
  describeMove,
  fitCardBottom,
  loadPlacement,
  parsePlacement,
  placementToPoint,
  restTilt,
  savePlacement,
  smoothTilt,
  snapPlacement,
  stepPlacement,
  velocityTilt,
  type FabGeometry,
} from "./notes-drag";

const g: FabGeometry = {
  width: 1000,
  height: 800,
  size: 56,
  top: 100,
  bottomLimit: 16,
  insetLeft: 20,
  insetRight: 20,
};

describe("createFabDrag", () => {
  it("touch: a still 400ms press picks up, anchored where it is", () => {
    const d = createFabDrag();
    expect(d.down("touch", 100, 100)).toBe(400);
    expect(d.move(105, 100)).toBe("none");
    expect(d.longPress()).toBe(true);
    expect(d.dragging).toBe(true);
    expect(d.move(125, 110)).toBe("drag");
    expect(d.offset).toEqual({ dx: 20, dy: 10 });
    expect(d.up()).toBe("drop");
  });

  it("touch: moving more than 8px before the timer cancels", () => {
    const d = createFabDrag();
    d.down("touch", 0, 0);
    expect(d.move(8, 0)).toBe("none");
    expect(d.move(9, 0)).toBe("cancel");
    expect(d.longPress()).toBe(false);
    expect(d.up()).toBe("none");
  });

  it("touch: a quick release is a tap", () => {
    const d = createFabDrag();
    d.down("touch", 0, 0);
    expect(d.up()).toBe("tap");
  });

  it("mouse: no long-press, picks up beyond 6px from the press point", () => {
    const d = createFabDrag();
    expect(d.down("mouse", 50, 50)).toBeNull();
    expect(d.move(56, 50)).toBe("none");
    expect(d.move(57, 50)).toBe("pickup");
    expect(d.offset).toEqual({ dx: 7, dy: 0 });
    expect(d.longPress()).toBe(false);
    expect(d.up()).toBe("drop");
  });

  it("mouse: release inside the slop is a tap", () => {
    const d = createFabDrag();
    d.down("mouse", 0, 0);
    d.move(3, 3);
    expect(d.up()).toBe("tap");
  });

  it("pen behaves like a fine pointer", () => {
    const d = createFabDrag();
    expect(d.down("pen", 0, 0)).toBeNull();
    expect(d.move(10, 0)).toBe("pickup");
  });

  it("cancel reports whether a drag was in progress", () => {
    const d = createFabDrag();
    d.down("mouse", 0, 0);
    expect(d.cancel()).toBe(false);
    d.down("mouse", 0, 0);
    d.move(20, 0);
    expect(d.cancel()).toBe(true);
    expect(d.phase).toBe("idle");
  });
});

describe("tilt", () => {
  it("scales velocity by 0.02 and clamps to 12 degrees", () => {
    expect(velocityTilt(300)).toBeCloseTo(6);
    expect(velocityTilt(-300)).toBeCloseTo(-6);
    expect(velocityTilt(5000)).toBe(12);
    expect(velocityTilt(-5000)).toBe(-12);
  });

  it("rests at +4 on the right and -4 on the left", () => {
    expect(restTilt("right")).toBe(4);
    expect(restTilt("left")).toBe(-4);
  });

  it("smooths towards the target and settles", () => {
    expect(smoothTilt(0, 10, 0)).toBe(0);
    const step = smoothTilt(0, 10, 16);
    expect(step).toBeGreaterThan(0);
    expect(step).toBeLessThan(10);
    expect(smoothTilt(0, 10, 2000)).toBeCloseTo(10, 1);
  });

  it("tracker follows a fast drag and decays when the pointer rests", () => {
    const t = createTiltTracker();
    expect(t.frame(0, 0)).toBe(0);
    let tilt = 0;
    for (let i = 1; i <= 30; i++) tilt = t.frame(i * 16, i * 16); // 1000px/s
    expect(tilt).toBeGreaterThan(10);
    expect(tilt).toBeLessThanOrEqual(12);
    for (let i = 31; i <= 90; i++) tilt = t.frame(30 * 16, i * 16);
    expect(Math.abs(tilt)).toBeLessThan(0.1);
  });
});

describe("geometry", () => {
  it("spans from the top limit to the bottom limit", () => {
    expect(bandHeight(g)).toBe(800 - 16 - 56 - 100);
    expect(placementToPoint({ side: "right", y: 0 }, g)).toEqual({
      x: 1000 - 20 - 56,
      y: 100,
    });
    expect(placementToPoint({ side: "left", y: 1 }, g)).toEqual({
      x: 20,
      y: 800 - 16 - 56,
    });
  });

  it("clamps out of range fractions", () => {
    expect(placementToPoint({ side: "left", y: 5 }, g).y).toBe(800 - 16 - 56);
    expect(placementToPoint({ side: "left", y: -5 }, g).y).toBe(100);
  });

  it("snaps to the nearest edge by the centre of the FAB", () => {
    expect(snapPlacement({ x: 400, y: 300 }, g).side).toBe("left");
    expect(snapPlacement({ x: 480, y: 300 }, g).side).toBe("right");
    expect(snapPlacement({ x: 472, y: 300 }, g).side).toBe("right"); // centre 500
  });

  it("clamps the dropped height into the band", () => {
    expect(snapPlacement({ x: 0, y: 0 }, g).y).toBe(0);
    expect(snapPlacement({ x: 0, y: 799 }, g).y).toBe(1);
    expect(snapPlacement({ x: 0, y: 100 + bandHeight(g) / 2 }, g).y).toBe(0.5);
  });

  it("keeps a dragged FAB inside the viewport", () => {
    expect(clampToViewport({ x: -30, y: 900 }, g)).toEqual({
      x: 0,
      y: 800 - 56,
    });
  });

  it("derives the default from the CSS spot on the right edge", () => {
    const d = defaultPlacement(300, g);
    expect(d.side).toBe("right");
    expect(placementToPoint(d, g).y).toBeCloseTo(300);
  });

  it("copes with a band that has no room", () => {
    const tiny = { ...g, height: 150 };
    expect(bandHeight(tiny)).toBe(0);
    expect(snapPlacement({ x: 0, y: 40 }, tiny).y).toBe(0);
  });
});

describe("keyboard", () => {
  const p = { side: "right", y: 0.5 } as const;

  it("moves 8% per vertical step and stops at the band", () => {
    expect(stepPlacement(p, "ArrowUp").y).toBeCloseTo(0.42);
    expect(stepPlacement(p, "ArrowDown").y).toBeCloseTo(0.58);
    expect(stepPlacement({ side: "left", y: 0.03 }, "ArrowUp").y).toBe(0);
    expect(stepPlacement({ side: "left", y: 0.98 }, "ArrowDown").y).toBe(1);
  });

  it("switches sides and keeps the height", () => {
    expect(stepPlacement(p, "ArrowLeft")).toEqual({ side: "left", y: 0.5 });
    expect(stepPlacement({ side: "left", y: 0.2 }, "ArrowRight")).toEqual({
      side: "right",
      y: 0.2,
    });
  });

  it("describes what moved, or nothing", () => {
    expect(describeMove(p, stepPlacement(p, "ArrowLeft"))).toBe("left");
    expect(describeMove(p, stepPlacement(p, "ArrowRight"))).toBeNull();
    expect(describeMove(p, stepPlacement(p, "ArrowUp"))).toBe("up");
    expect(describeMove(p, stepPlacement(p, "ArrowDown"))).toBe("down");
  });
});

describe("fitCardBottom", () => {
  it("stays on the FAB when the card fits", () => {
    expect(fitCardBottom(200, 300, g)).toBe(200);
  });

  it("slides down when the card would leave the top of the band", () => {
    // 800 - 100 - 500 = 200 is the highest allowed bottom offset.
    expect(fitCardBottom(600, 500, g)).toBe(200);
  });

  it("never goes below the bottom limit", () => {
    expect(fitCardBottom(5, 300, g)).toBe(16);
    expect(fitCardBottom(300, 900, g)).toBe(16);
  });
});

describe("persistence", () => {
  it("round-trips a placement", () => {
    const store = new Map<string, string>();
    const storage = {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    };
    savePlacement({ side: "left", y: 0.25 }, storage);
    expect(store.has(STORAGE_KEY)).toBe(true);
    expect(loadPlacement(storage)).toEqual({ side: "left", y: 0.25 });
  });

  it("rejects junk and clamps y", () => {
    expect(parsePlacement(null)).toBeNull();
    expect(parsePlacement("nope")).toBeNull();
    expect(parsePlacement('{"side":"top","y":0.5}')).toBeNull();
    expect(parsePlacement('{"side":"left","y":"1"}')).toBeNull();
    expect(parsePlacement('{"side":"right","y":4}')).toEqual({
      side: "right",
      y: 1,
    });
  });

  it("falls back to null when storage throws", () => {
    const broken = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    };
    expect(loadPlacement(broken)).toBeNull();
    expect(() => savePlacement({ side: "left", y: 0 }, broken)).not.toThrow();
  });
});
