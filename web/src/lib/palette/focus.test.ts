import { describe, expect, it } from "vitest";

import { shouldRefocusSearch } from "./focus.ts";

const inside = new Set<object>();
const contains = (target: object) => inside.has(target);

describe("shouldRefocusSearch", () => {
  it("takes focus back when it fell to nowhere while open", () => {
    expect(
      shouldRefocusSearch({ open: true, relatedTarget: null, contains }),
    ).toBe(true);
  });

  it("leaves focus alone when the palette is closed", () => {
    expect(
      shouldRefocusSearch({ open: false, relatedTarget: null, contains }),
    ).toBe(false);
  });

  it("leaves focus alone when it moved inside the dialog", () => {
    const row = {};
    inside.add(row);
    expect(
      shouldRefocusSearch({ open: true, relatedTarget: row, contains }),
    ).toBe(false);
  });

  it("takes focus back from an element outside the dialog", () => {
    const outside = {};
    expect(
      shouldRefocusSearch({ open: true, relatedTarget: outside, contains }),
    ).toBe(true);
  });
});
