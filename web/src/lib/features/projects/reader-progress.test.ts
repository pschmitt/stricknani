import { beforeEach, describe, expect, it } from "vitest";
import {
  rememberReadingStep,
  resolveReadingStep,
  stepHash,
  stepIdFromHash,
} from "./reader-progress";

describe("project reader position", () => {
  beforeEach(() => {
    rememberReadingStep(1, 50, 501);
    rememberReadingStep(1, 50, 502);
    rememberReadingStep(2, 50, 503);
    rememberReadingStep(1, 51, 504);
  });

  it("parses only hashes for a step present in the current project", () => {
    expect(stepIdFromHash("#step-502", [501, 502])).toBe(502);
    expect(stepIdFromHash("#step-999", [501, 502])).toBeNull();
    expect(stepIdFromHash("#step-0", [501, 502])).toBeNull();
    expect(stepIdFromHash("#step-5x", [501, 502])).toBeNull();
  });

  it("restores the last in-memory step per user and project", () => {
    expect(resolveReadingStep(1, 50, "", [501, 502])).toBe(502);
    expect(resolveReadingStep(2, 50, "", [503])).toBe(503);
    expect(resolveReadingStep(1, 51, "", [504])).toBe(504);
    expect(resolveReadingStep(1, 52, "", [505, 506])).toBe(505);
  });

  it("uses a valid addressable step over the remembered position", () => {
    expect(resolveReadingStep(1, 50, "#step-501", [501, 502])).toBe(501);
    expect(stepHash(502)).toBe("#step-502");
    expect(stepHash(0)).toBe("");
  });
});
