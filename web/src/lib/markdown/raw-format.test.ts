import { describe, expect, it } from "vitest";
import { applyRawFormat } from "./raw-format.ts";

describe("applyRawFormat", () => {
  it("wraps and unwraps bold", () => {
    const on = applyRawFormat({ text: "a word b", start: 2, end: 6 }, "bold");
    expect(on).toEqual({ text: "a **word** b", start: 4, end: 8 });
    expect(applyRawFormat(on, "bold")).toEqual({
      text: "a word b",
      start: 2,
      end: 6,
    });
  });

  it("does not take the edge of bold for italic", () => {
    const out = applyRawFormat({ text: "**x**", start: 2, end: 3 }, "italic");
    expect(out.text).toBe("***x***");
  });

  it("numbers every selected line and removes the numbers again", () => {
    const on = applyRawFormat({ text: "a\nb", start: 0, end: 3 }, "ordered");
    expect(on.text).toBe("1. a\n2. b");
    expect(applyRawFormat(on, "ordered").text).toBe("a\nb");
  });

  it("swaps a bullet for a heading", () => {
    expect(
      applyRawFormat({ text: "- Gauge", start: 3, end: 3 }, "heading").text,
    ).toBe("### Gauge");
  });

  it("inserts a link around the selection and selects the address", () => {
    const out = applyRawFormat({ text: "see this", start: 4, end: 8 }, "link");
    expect(out).toEqual({ text: "see [this](https://)", start: 11, end: 19 });
  });
});
