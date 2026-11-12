import { describe, expect, it, vi } from "vitest";
import { clientLabel, parseAuditClient } from "./audit-client";

vi.mock("#lib/i18n.svelte.ts", () => ({
  t: (message: string) => message,
}));

describe("parseAuditClient", () => {
  it("reads the server's client block", () => {
    expect(
      parseAuditClient({
        kind: "android",
        version: "1.7.1 (code 123; debug)",
        label: "Android 1.7.1 (debug)",
        user_agent: "okhttp/4.12.0",
      }),
    ).toEqual({
      kind: "android",
      version: "1.7.1 (code 123; debug)",
      label: "Android 1.7.1 (debug)",
      user_agent: "okhttp/4.12.0",
    });
  });

  it("reads missing, unrecognised and malformed blocks as an unknown client", () => {
    const unknown = {
      kind: "unknown",
      version: null,
      label: null,
      user_agent: null,
    };
    expect(parseAuditClient(undefined)).toEqual(unknown);
    expect(parseAuditClient(null)).toEqual(unknown);
    expect(parseAuditClient("web")).toEqual(unknown);
    expect(parseAuditClient({ kind: "toaster", label: 5 })).toEqual(unknown);
  });
});

describe("clientLabel", () => {
  const base = { version: null, label: null, user_agent: null };

  it("shows the server's label for web, Android and API clients", () => {
    expect(
      clientLabel({ ...base, kind: "web", label: "Firefox 159 · Linux" }, "en"),
    ).toBe("Firefox 159 · Linux");
    expect(
      clientLabel(
        { ...base, kind: "android", label: "Android 1.7.1 (debug)" },
        "en",
      ),
    ).toBe("Android 1.7.1 (debug)");
  });

  it("names the kind when there is no label", () => {
    expect(clientLabel({ ...base, kind: "web" }, "en")).toBe("Web");
    expect(clientLabel({ ...base, kind: "android" }, "en")).toBe("Android");
    expect(clientLabel({ ...base, kind: "api" }, "en")).toBe("API");
  });

  it("labels system and unknown clients", () => {
    expect(clientLabel({ ...base, kind: "system" }, "en")).toBe("System");
    expect(clientLabel({ ...base, kind: "unknown" }, "en")).toBe(
      "Unknown client",
    );
  });
});
