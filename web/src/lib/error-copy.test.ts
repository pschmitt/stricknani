import { describe, expect, it } from "vitest";
import { ERROR_COPY, errorKindForStatus, pickQuip } from "./error-copy.ts";

describe("errorKindForStatus", () => {
  it("maps missing routes and permissions", () => {
    expect(errorKindForStatus(404, "Not Found", true)).toBe("not-found");
    expect(errorKindForStatus(403, "", true)).toBe("forbidden");
    expect(errorKindForStatus(401, "", true)).toBe("forbidden");
  });

  it("treats a dropped connection as offline", () => {
    expect(errorKindForStatus(500, "anything", false)).toBe("offline");
    expect(errorKindForStatus(0, "", true)).toBe("offline");
    expect(errorKindForStatus(500, "Failed to fetch", true)).toBe("offline");
    expect(
      errorKindForStatus(
        500,
        "Failed to fetch dynamically imported module: /app/x.js",
        true,
      ),
    ).toBe("offline");
  });

  it("falls back to the tangled server page", () => {
    expect(errorKindForStatus(500, "Internal Error", true)).toBe("server");
    expect(errorKindForStatus(502, "", true)).toBe("server");
  });
});

describe("pickQuip", () => {
  it("is stable for a seed and always returns one of the quips", () => {
    const quips = ["a", "b", "c"];
    expect(pickQuip("/app/nope", quips)).toBe(pickQuip("/app/nope", quips));
    for (const seed of ["", "/a", "/b/c", "/app/projects/9"]) {
      expect(quips).toContain(pickQuip(seed, quips));
    }
    expect(pickQuip("x", [])).toBe("");
  });
});

describe("ERROR_COPY", () => {
  it("has a full headline, body and kicker for every kind", () => {
    for (const copy of Object.values(ERROR_COPY)) {
      expect(copy.kicker.length).toBeGreaterThan(0);
      expect(copy.title.length).toBeGreaterThan(0);
      expect(copy.body.length).toBeGreaterThan(0);
      expect(copy.quips.length).toBeGreaterThan(0);
    }
  });
});
