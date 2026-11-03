import { describe, expect, it } from "vitest";
import { formatLastUsed, setupUri } from "./connect-app.ts";

describe("setupUri", () => {
  it("encodes the envelope the Android app decodes", () => {
    const uri = setupUri("https://example.test", "tok+/en", 1700000000000);
    expect(uri.startsWith("stricknani://setup?p=")).toBe(true);
    const payload = uri.slice("stricknani://setup?p=".length);
    expect(payload).not.toMatch(/[+/=]/);
    const json = atob(payload.replaceAll("-", "+").replaceAll("_", "/"));
    expect(JSON.parse(json)).toEqual({
      version: 1,
      createdAt: 1700000000000,
      baseUrl: "https://example.test",
      token: "tok+/en",
    });
  });
});

describe("formatLastUsed", () => {
  const now = Date.parse("2026-11-03T12:00:00Z");
  it("is relative for recent use", () => {
    expect(formatLastUsed("2026-11-03T11:59:30Z", "en", now)).toBe(
      "this minute",
    );
    expect(formatLastUsed("2026-11-03T09:00:00Z", "en", now)).toBe(
      "3 hours ago",
    );
    expect(formatLastUsed("2026-11-01T12:00:00Z", "en", now)).toBe(
      "2 days ago",
    );
  });
  it("falls back to a date after a month and keeps junk as is", () => {
    expect(formatLastUsed("2026-08-01T12:00:00Z", "en", now)).toBe(
      "Aug 1, 2026",
    );
    expect(formatLastUsed("nonsense", "en", now)).toBe("nonsense");
  });
});
