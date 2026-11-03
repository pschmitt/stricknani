import { describe, expect, it } from "vitest";
import {
  addSpin,
  clampEyeOffset,
  flickToOmega,
  frictionStep,
  isDizzy,
  isSettled,
  lookDirection,
  MAX_OMEGA,
  nextFidgetDelay,
  pickFidget,
  RUBBER_LIMIT,
  rubberBand,
  rubberBand2d,
  spinStep,
  springStep,
} from "./physics";

describe("rubberBand", () => {
  it("is ~linear near zero, odd and bounded by the limit", () => {
    expect(rubberBand(0)).toBe(0);
    expect(rubberBand(1)).toBeCloseTo(1, 1);
    expect(rubberBand(-10)).toBeCloseTo(-rubberBand(10));
    expect(rubberBand(10_000)).toBeLessThanOrEqual(RUBBER_LIMIT);
    expect(rubberBand(10_000)).toBeGreaterThan(RUBBER_LIMIT - 0.01);
  });

  it("is monotonic", () => {
    expect(rubberBand(40)).toBeGreaterThan(rubberBand(20));
  });

  it("keeps the direction in 2D", () => {
    const v = rubberBand2d(300, 400);
    expect(Math.hypot(v.x, v.y)).toBeLessThanOrEqual(RUBBER_LIMIT);
    expect(v.y / v.x).toBeCloseTo(4 / 3);
    expect(rubberBand2d(0, 0)).toEqual({ x: 0, y: 0 });
  });
});

describe("springStep", () => {
  it("overshoots and then settles at the target", () => {
    let state = { x: 20, v: 0 };
    let crossed = false;
    for (let i = 0; i < 600; i += 1) {
      state = springStep(state, 0, 1 / 60);
      if (state.x < -0.5) crossed = true;
    }
    expect(crossed).toBe(true);
    expect(Math.abs(state.x)).toBeLessThan(0.01);
    expect(Math.abs(state.v)).toBeLessThan(0.01);
  });

  it("stays stable for a huge time step", () => {
    const state = springStep({ x: 20, v: 0 }, 0, 5);
    expect(Number.isFinite(state.x)).toBe(true);
    expect(Math.abs(state.x)).toBeLessThan(40);
  });
});

describe("flick and friction", () => {
  it("ignores slow releases", () => {
    expect(flickToOmega(100, 50)).toBe(0);
  });

  it("spins in the direction of the flick and caps the speed", () => {
    expect(flickToOmega(1000, 0)).toBeGreaterThan(0);
    expect(flickToOmega(-1000, 0)).toBeLessThan(0);
    expect(flickToOmega(0, 900)).toBeGreaterThan(0);
    expect(flickToOmega(99_999, 0)).toBe(MAX_OMEGA);
  });

  it("adds spin within the cap", () => {
    expect(addSpin(100, 200)).toBe(300);
    expect(addSpin(MAX_OMEGA, 500)).toBe(MAX_OMEGA);
    expect(addSpin(-MAX_OMEGA, -500)).toBe(-MAX_OMEGA);
  });

  it("decays exponentially", () => {
    const once = frictionStep(1000, 0.1);
    expect(once).toBeCloseTo(1000 * Math.exp(-0.22));
    expect(frictionStep(frictionStep(1000, 0.05), 0.05)).toBeCloseTo(once);
  });

  it("flags dizzy above the threshold only", () => {
    expect(isDizzy(500)).toBe(true);
    expect(isDizzy(-500)).toBe(true);
    expect(isDizzy(100)).toBe(false);
  });
});

describe("spinStep", () => {
  it("slows down and settles on a whole turn", () => {
    let spin = { angle: 0, omega: 1500 };
    for (let i = 0; i < 1200; i += 1) spin = spinStep(spin, 1 / 60);
    expect(
      Math.abs(spin.angle - Math.round(spin.angle / 360) * 360),
    ).toBeLessThan(0.5);
    expect(Math.abs(spin.omega)).toBeLessThan(0.5);
    expect(spin.angle).toBeGreaterThan(360);
  });
});

describe("isSettled", () => {
  const zero = { x: 0, y: 0 };
  it("is true at rest, also on a whole turn", () => {
    expect(isSettled(zero, zero, { angle: 720, omega: 0 })).toBe(true);
  });
  it("is false while moving or offset", () => {
    expect(isSettled({ x: 1, y: 0 }, zero, { angle: 0, omega: 0 })).toBe(false);
    expect(isSettled(zero, { x: 0, y: 3 }, { angle: 0, omega: 0 })).toBe(false);
    expect(isSettled(zero, zero, { angle: 0, omega: 50 })).toBe(false);
    expect(isSettled(zero, zero, { angle: 90, omega: 0 })).toBe(false);
  });
});

describe("eyes", () => {
  it("looks toward the target with a bounded magnitude", () => {
    const right = lookDirection(100, 0);
    expect(right.x).toBeGreaterThan(0);
    expect(right.y).toBe(0);
    const far = lookDirection(5000, 5000);
    expect(Math.hypot(far.x, far.y)).toBeCloseTo(1);
    expect(lookDirection(0, 0)).toEqual({ x: 0, y: 0 });
  });

  it("clamps the eye offset to 1.5", () => {
    const c = clampEyeOffset(30, 40);
    expect(Math.hypot(c.x, c.y)).toBeCloseTo(1.5);
    expect(clampEyeOffset(0.5, 0.5)).toEqual({ x: 0.5, y: 0.5 });
  });
});

describe("fidgets", () => {
  it("waits between 8 and 15 seconds", () => {
    expect(nextFidgetDelay(() => 0)).toBe(8000);
    expect(nextFidgetDelay(() => 0.999999)).toBeLessThanOrEqual(15000);
    expect(nextFidgetDelay(() => 0.5)).toBe(11500);
  });

  it("picks every fidget and never overflows", () => {
    expect(pickFidget(() => 0)).toBe("blink");
    expect(pickFidget(() => 0.5)).toBe("look-around");
    expect(pickFidget(() => 0.99)).toBe("micro-bounce");
    expect(pickFidget(() => 1)).toBe("micro-bounce");
  });
});
