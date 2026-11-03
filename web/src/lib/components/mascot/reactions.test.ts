import { describe, expect, it } from "vitest";
import {
  advancePlayback,
  confettiParticles,
  heartParticles,
  idlePlayback,
  initialTapState,
  nextReaction,
  poseAt,
  REACTION_DURATION,
  REACTIONS,
  registerTap,
  requestReaction,
} from "./reactions";

describe("nextReaction", () => {
  it("starts with the first reaction", () => {
    expect(nextReaction(null, () => 0.5)).toBe("squish");
  });

  it("follows the sequence 70% of the time and wraps", () => {
    expect(nextReaction("squish", () => 0.1)).toBe("wink");
    expect(nextReaction("giggle", () => 0.1)).toBe("squish");
  });

  it("otherwise picks a different random one", () => {
    for (const last of REACTIONS) {
      for (const second of [0, 0.3, 0.6, 0.99]) {
        const values = [0.8, second];
        const pick = nextReaction(last, () => values.shift() ?? 0);
        expect(pick).not.toBe(last);
      }
    }
  });
});

describe("playback queue", () => {
  it("starts immediately when idle", () => {
    const p = requestReaction(idlePlayback, "wink", 1000);
    expect(p.current).toEqual({ kind: "wink", startedAt: 1000 });
    expect(p.queued).toBeNull();
  });

  it("queues at most one extra reaction", () => {
    let p = requestReaction(idlePlayback, "squish", 0);
    p = requestReaction(p, "wink", 100);
    p = requestReaction(p, "spin", 150);
    expect(p.queued).toBe("wink");
    expect(p.current?.kind).toBe("squish");
  });

  it("plays the queued reaction after the current finishes, then idles", () => {
    let p = requestReaction(idlePlayback, "squish", 0);
    p = requestReaction(p, "wink", 100);
    expect(advancePlayback(p, 100)).toBe(p);
    const end = REACTION_DURATION.squish;
    p = advancePlayback(p, end + 5);
    expect(p.current?.kind).toBe("wink");
    expect(p.queued).toBeNull();
    p = advancePlayback(p, end + 5 + REACTION_DURATION.wink + 1);
    expect(p).toEqual(idlePlayback);
  });

  it("accepts a request once the previous one is over", () => {
    const p = requestReaction(idlePlayback, "squish", 0);
    const next = requestReaction(p, "giggle", REACTION_DURATION.squish + 1);
    expect(next.current?.kind).toBe("giggle");
  });
});

describe("registerTap", () => {
  it("counts quick taps and fires the easter egg on the seventh", () => {
    let state = initialTapState;
    let fired = false;
    for (let i = 0; i < 7; i += 1) {
      const r = registerTap(state, i * 300);
      state = r.state;
      fired = r.easterEgg;
      if (i < 6) expect(r.easterEgg).toBe(false);
    }
    expect(fired).toBe(true);
    expect(state).toEqual(initialTapState);
  });

  it("restarts the streak after a slow tap", () => {
    let state = initialTapState;
    for (let i = 0; i < 5; i += 1) state = registerTap(state, i * 300).state;
    const slow = registerTap(state, 5 * 300 + 451);
    expect(slow.quick).toBe(false);
    expect(slow.state.count).toBe(1);
  });

  it("counts a gap of exactly 450ms as quick", () => {
    const a = registerTap(initialTapState, 0);
    expect(registerTap(a.state, 450).quick).toBe(true);
  });

  it("does not fire when not allowed (once per session)", () => {
    let state = initialTapState;
    let any = false;
    for (let i = 0; i < 10; i += 1) {
      const r = registerTap(state, i * 100, false);
      state = r.state;
      any ||= r.easterEgg;
    }
    expect(any).toBe(false);
  });
});

describe("poseAt", () => {
  it("squish starts squashed (wider and shorter) and settles to rest", () => {
    const start = poseAt("squish", 0);
    expect(start.sx).toBeCloseTo(1.15);
    expect(start.sy).toBeCloseTo(0.85);
    const end = poseAt("squish", 1);
    expect(end.sx).toBeCloseTo(1, 1);
    expect(end.sy).toBeCloseTo(1, 1);
  });

  it("wink closes both eyes, then swaps, then returns to normal", () => {
    expect(poseAt("wink", 0.1).eyes).toBe("happy");
    expect(poseAt("wink", 0.5).eyes).toBe("swap");
    expect(poseAt("wink", 0.95).eyes).toBe("normal");
  });

  it("spin makes one full turn with a hop", () => {
    expect(poseAt("spin", 1).rot).toBeCloseTo(360);
    expect(poseAt("spin", 0.5).dy).toBeLessThan(0);
  });

  it("giggle makes the cheeks rosier mid-way", () => {
    expect(poseAt("giggle", 0.4).cheek).toBeGreaterThan(0.5);
  });

  it("clamps progress", () => {
    expect(poseAt("spin", 2).rot).toBeCloseTo(360);
    expect(poseAt("bounce", -1).dy).toBeCloseTo(0);
  });
});

describe("particles", () => {
  it("makes 3 to 5 hearts rising upward", () => {
    for (const r of [0, 0.5, 0.99]) {
      const hearts = heartParticles(() => r);
      expect(hearts.length).toBeGreaterThanOrEqual(3);
      expect(hearts.length).toBeLessThanOrEqual(5);
      expect(hearts.every((h) => h.kind === "heart" && h.dy < 0)).toBe(true);
    }
  });

  it("makes confetti of tiny yarn balls in all directions", () => {
    const confetti = confettiParticles(() => 0.5);
    expect(confetti).toHaveLength(24);
    expect(confetti.every((p) => p.kind === "yarn")).toBe(true);
    expect(confetti.some((p) => p.dx > 5)).toBe(true);
    expect(confetti.some((p) => p.dx < -5)).toBe(true);
    expect(confetti.some((p) => p.dy > 5)).toBe(true);
    expect(confetti.some((p) => p.dy < -5)).toBe(true);
  });
});
