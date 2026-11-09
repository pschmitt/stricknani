import { describe, expect, it } from "vitest";
import {
  currentStep,
  formatElapsed,
  importSteps,
  progressFraction,
} from "./import-progress";

describe("import progress", () => {
  it("walks a link import through its steps and stays on the last", () => {
    const steps = importSteps("url", true);
    expect(steps.map((step) => step.id)).toEqual([
      "fetch",
      "pictures",
      "read",
      "review",
    ]);
    expect(currentStep(steps, 0)).toBe(0);
    expect(currentStep(steps, 6_000)).toBe(1);
    expect(currentStep(steps, 20_000)).toBe(2);
    expect(currentStep(steps, 10 * 60_000)).toBe(3);
  });

  it("is quicker without AI", () => {
    expect(currentStep(importSteps("url", false), 3_500)).toBe(2);
    expect(currentStep(importSteps("url", true), 3_500)).toBe(0);
  });

  it("never fills the bar before the result", () => {
    expect(progressFraction(0, true)).toBe(0);
    expect(progressFraction(30_000, true)).toBeGreaterThan(0.5);
    expect(progressFraction(60 * 60_000, true)).toBeLessThanOrEqual(0.95);
    expect(progressFraction(4_000, false)).toBeGreaterThan(
      progressFraction(4_000, true),
    );
  });

  it("formats the elapsed time", () => {
    expect(formatElapsed(7_400)).toBe("0:07");
    expect(formatElapsed(83_000)).toBe("1:23");
  });
});
