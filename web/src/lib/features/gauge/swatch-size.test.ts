import { describe, expect, it } from "vitest";
import {
  defaultSwatchSize,
  formatCm,
  isDefaultSize,
  parseCm,
  sizeLabel,
  swatchAspect,
} from "./swatch-size";

describe("swatch size", () => {
  it("parses decimals and rejects out-of-range values", () => {
    expect(parseCm("12.5")).toBe(12.5);
    expect(parseCm("12,5")).toBe(12.5);
    expect(parseCm("100")).toBe(100);
    for (const bad of ["", "0", "-1", "100.5", "abc", "1e2", "1.2.3"]) {
      expect(parseCm(bad)).toBeNull();
    }
  });

  it("labels and detects the default", () => {
    expect(sizeLabel(defaultSwatchSize())).toBe("10 × 10 cm");
    expect(sizeLabel({ width: 12.5, height: 8 })).toBe("12.5 × 8 cm");
    expect(isDefaultSize(defaultSwatchSize())).toBe(true);
    expect(isDefaultSize({ width: 10, height: 12 })).toBe(false);
    expect(formatCm(10.004)).toBe("10");
  });

  it("keeps the aspect ratio with the longer side at 1", () => {
    expect(swatchAspect({ width: 20, height: 10 })).toEqual({ w: 1, h: 0.5 });
    expect(swatchAspect(defaultSwatchSize())).toEqual({ w: 1, h: 1 });
  });
});
