import { describe, expect, it } from "vitest";
import {
  dismissParticles,
  dragLook,
  lockAxis,
  swipeOutcome,
} from "./swipe-dismiss.ts";

describe("lockAxis", () => {
  it("waits until the finger moved past the slop", () => {
    expect(lockAxis(3, 2)).toBeUndefined();
  });
  it("locks horizontal only when it clearly dominates", () => {
    expect(lockAxis(20, 4)).toBe("horizontal");
    expect(lockAxis(-20, 4)).toBe("horizontal");
    expect(lockAxis(14, 12)).toBeUndefined();
  });
  it("locks vertical so scrolling keeps working", () => {
    expect(lockAxis(4, 20)).toBe("vertical");
    expect(lockAxis(12, 12)).toBe("vertical");
  });
});

describe("swipeOutcome", () => {
  const width = 400;
  it("dismisses past the threshold in either direction", () => {
    expect(swipeOutcome({ dx: 160, width, velocity: 0 })).toEqual({
      kind: "dismiss",
      direction: 1,
    });
    expect(swipeOutcome({ dx: -160, width, velocity: 0 })).toEqual({
      kind: "dismiss",
      direction: -1,
    });
  });
  it("springs back short of the threshold when slow", () => {
    expect(swipeOutcome({ dx: 100, width, velocity: 0.1 })).toEqual({
      kind: "restore",
    });
  });
  it("dismisses on a fast fling in the drag direction", () => {
    expect(swipeOutcome({ dx: 60, width, velocity: 1.2 })).toEqual({
      kind: "dismiss",
      direction: 1,
    });
  });
  it("ignores a fling against the drag or a tiny twitch", () => {
    expect(swipeOutcome({ dx: 60, width, velocity: -1.2 }).kind).toBe(
      "restore",
    );
    expect(swipeOutcome({ dx: 10, width, velocity: 2 }).kind).toBe("restore");
  });
  it("never dismisses without a width or movement", () => {
    expect(swipeOutcome({ dx: 0, width, velocity: 3 }).kind).toBe("restore");
    expect(swipeOutcome({ dx: 50, width: 0, velocity: 3 }).kind).toBe(
      "restore",
    );
  });
});

describe("dragLook", () => {
  it("tilts with the drag direction and fades with distance", () => {
    expect(dragLook(0, 400)).toEqual({ rotate: 0, opacity: 1 });
    const right = dragLook(200, 400);
    const left = dragLook(-200, 400);
    expect(right.rotate).toBeGreaterThan(0);
    expect(left.rotate).toBeLessThan(0);
    expect(right.opacity).toBeLessThan(1);
    expect(dragLook(9999, 400).opacity).toBeGreaterThanOrEqual(0.25);
  });
});

describe("dismissParticles", () => {
  it("puffs out a mix of hearts and yarn", () => {
    const list = dismissParticles(() => 0.5);
    expect(list).toHaveLength(9);
    expect(new Set(list.map((p) => p.kind))).toEqual(
      new Set(["heart", "yarn"]),
    );
  });
});
