import { describe, expect, it } from "vitest";
import { parseGaugeValues, type GaugeValue } from "./gauge-input";

const complete = {
  pattern_gauge_stitches: 20,
  pattern_gauge_rows: 28,
  user_gauge_stitches: 22,
  user_gauge_rows: 30,
  pattern_cast_on_stitches: 80,
  pattern_row_count: undefined as GaugeValue,
};

describe("gauge input parsing", () => {
  it("accepts a complete set without the optional row count", () => {
    const { input, states } = parseGaugeValues(complete);
    expect(input).toEqual({
      pattern_gauge_stitches: 20,
      pattern_gauge_rows: 28,
      user_gauge_stitches: 22,
      user_gauge_rows: 30,
      pattern_cast_on_stitches: 80,
    });
    expect(states.pattern_row_count).toBe("ok");
  });

  it("includes a valid optional row count, also as a numeric string", () => {
    expect(
      parseGaugeValues({ ...complete, pattern_row_count: "100" }).input
        ?.pattern_row_count,
    ).toBe(100);
  });

  it("treats missing required fields as empty, not invalid", () => {
    const { input, states } = parseGaugeValues({
      ...complete,
      user_gauge_rows: null,
      pattern_cast_on_stitches: "  ",
    });
    expect(input).toBeNull();
    expect(states.user_gauge_rows).toBe("empty");
    expect(states.pattern_cast_on_stitches).toBe("empty");
    expect(states.pattern_gauge_rows).toBe("ok");
  });

  it("flags zero, negative, fractional and oversized values as invalid", () => {
    for (const bad of [0, -3, 1.5, 4294967296, "abc"]) {
      const { input, states } = parseGaugeValues({
        ...complete,
        user_gauge_stitches: bad,
      });
      expect(input).toBeNull();
      expect(states.user_gauge_stitches).toBe("invalid");
    }
  });

  it("blocks the request when a present optional value is invalid", () => {
    const { input, states } = parseGaugeValues({
      ...complete,
      pattern_row_count: 0,
    });
    expect(input).toBeNull();
    expect(states.pattern_row_count).toBe("invalid");
  });
});
