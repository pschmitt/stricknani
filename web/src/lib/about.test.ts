import { describe, expect, it } from "vitest";
import {
  credits,
  displayVersion,
  emptyServerBuild,
  parseServerBuild,
  shortBuildId,
  versionReport,
} from "#lib/about.ts";

describe("about helpers", () => {
  it("reads version and build id from the meta envelope", () => {
    expect(
      parseServerBuild({
        data: { version: "1.2.3", build_id: "abcdef0123456789" },
      }),
    ).toEqual({ version: "1.2.3", buildId: "abcdef0123456789" });
  });

  it("tolerates a missing or malformed meta response", () => {
    expect(parseServerBuild(null)).toEqual(emptyServerBuild);
    expect(parseServerBuild({ data: { version: 3 } })).toEqual(
      emptyServerBuild,
    );
  });

  it("shortens the build id", () => {
    expect(shortBuildId("abcdef0123456789")).toBe("abcdef01");
    expect(shortBuildId(" ")).toBeNull();
    expect(shortBuildId(null)).toBeNull();
  });

  it("falls back to the bundled web version", () => {
    expect(displayVersion(emptyServerBuild, "v0.1.0")).toBe("v0.1.0");
    expect(displayVersion({ version: "1.2.3", buildId: null }, "v0.1.0")).toBe(
      "v1.2.3",
    );
  });

  it("builds a report for bug reports", () => {
    expect(
      versionReport(
        { version: "1.2.3", buildId: "abcdef0123456789" },
        "v0.1.0",
      ),
    ).toBe("Stricknani v1.2.3\nBuild: abcdef01\nWeb: v0.1.0");
    expect(versionReport(emptyServerBuild, "v0.1.0")).toBe(
      "Stricknani v0.1.0\nWeb: v0.1.0",
    );
  });

  it("credits the shipped icons and fonts", () => {
    expect(credits.map((credit) => credit.name)).toEqual([
      "Phosphor Icons",
      "Fraunces",
      "Nunito",
    ]);
  });
});
