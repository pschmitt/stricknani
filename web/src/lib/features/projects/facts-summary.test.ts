import { describe, expect, it } from "vitest";
import { extraCount, plainTextSummary } from "./facts-summary";

describe("plainTextSummary", () => {
  it("strips images, entities and collapses whitespace", () => {
    expect(
      plainTextSummary(
        "zu erhalten: 18 M. x 23 R.  &nbsp; ![1000079183.jpg](/media/a b.jpg)\n\nEnde",
      ),
    ).toBe("zu erhalten: 18 M. x 23 R. Ende");
  });

  it("keeps link text but drops the target", () => {
    expect(plainTextSummary("see [DROPS Nr. 4](https://x.test/a) now")).toBe(
      "see DROPS Nr. 4 now",
    );
  });

  it("decodes numeric entities and leaves unknown ones", () => {
    expect(plainTextSummary("a&#160;b &amp; c &#x41; &bogus;")).toBe(
      "a b & c A &bogus;",
    );
  });

  it("handles empty values", () => {
    expect(plainTextSummary(null)).toBe("");
    expect(plainTextSummary("  ![x](y)  ")).toBe("");
  });
});

describe("extraCount", () => {
  it("counts yarns beyond the first", () => {
    expect(extraCount(0)).toBe(0);
    expect(extraCount(1)).toBe(0);
    expect(extraCount(6)).toBe(5);
  });
});
