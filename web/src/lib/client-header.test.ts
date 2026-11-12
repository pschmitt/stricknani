import { describe, expect, it } from "vitest";
import { clientHeader, clientHeaderValue } from "./client-header.ts";

describe("clientHeaderValue", () => {
  it("names the web app, its version and the build commit", () => {
    expect(clientHeaderValue("1.7.1", "9a7bfc70aabbccdd")).toBe(
      "web/1.7.1 (build 9a7bfc70)",
    );
    expect(clientHeaderValue("1.7.1", "9a7bfc70-dirty")).toBe(
      "web/1.7.1 (build 9a7bfc70-dirty)",
    );
  });

  it("omits the build when the commit is unknown or malformed", () => {
    expect(clientHeaderValue("1.7.1", "")).toBe("web/1.7.1");
    expect(clientHeaderValue("1.7.1", "unknown")).toBe("web/1.7.1");
    expect(clientHeaderValue("1.7.1", "not a sha!")).toBe("web/1.7.1");
  });

  it("never sends characters the server would reject", () => {
    expect(clientHeaderValue("1.7 1", "")).toBe("web/unknown");
    expect(clientHeaderValue("", "")).toBe("web/unknown");
    expect(clientHeaderValue("x".repeat(40), "")).toBe("web/unknown");
  });

  it("has a value for this bundle that the server grammar accepts", () => {
    expect(clientHeader).toMatch(
      /^web\/[0-9A-Za-z._+-]{1,32}( \(build [0-9a-f]{8}(-dirty)?\))?$/,
    );
  });
});
