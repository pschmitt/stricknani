import { describe, expect, it } from "vitest";
import { burstParticles } from "./favorite-burst";

describe("burstParticles", () => {
  it("returns the requested count, starting straight up", () => {
    const particles = burstParticles(8);
    expect(particles).toHaveLength(8);
    expect(particles[0].dx).toBeCloseTo(0);
    expect(particles[0].dy).toBeCloseTo(-24);
  });

  it("alternates near and far particles", () => {
    const [near, far] = burstParticles(8);
    expect(Math.hypot(far.dx, far.dy)).toBeCloseTo(34, 1);
    expect(Math.hypot(near.dx, near.dy)).toBeCloseTo(24, 1);
    expect(far.size).toBeLessThan(near.size);
  });

  it("spreads particles over both sides", () => {
    const particles = burstParticles(8);
    expect(particles.some((p) => p.dx > 1)).toBe(true);
    expect(particles.some((p) => p.dx < -1)).toBe(true);
  });
});
