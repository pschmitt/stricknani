import { describe, expect, it } from "vitest";
import { stepIdFromHash } from "./reader-progress";

describe("project reader step links", () => {
  it("parses only hashes for a step present in the current project", () => {
    expect(stepIdFromHash("#step-502", [501, 502])).toBe(502);
    expect(stepIdFromHash("#step-999", [501, 502])).toBeNull();
    expect(stepIdFromHash("#step-0", [501, 502])).toBeNull();
    expect(stepIdFromHash("#step-5x", [501, 502])).toBeNull();
    expect(stepIdFromHash("", [501, 502])).toBeNull();
  });
});
