import { describe, expect, it } from "vitest";
import {
  credits,
  displayVersion,
  emptyServerBuild,
  parseCommit,
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

describe("commit helpers", () => {
  it("formats a short SHA and builds the GitHub URL", () => {
    expect(parseCommit("9a7bfc70")).toEqual({
      display: "9a7bfc70",
      url: "https://github.com/pschmitt/stricknani/commit/9a7bfc70",
      full: "9a7bfc70",
    });
  });

  it("keeps -dirty in the label but not in the URL", () => {
    const commit = parseCommit("9a7bfc70-dirty");
    expect(commit?.display).toBe("9a7bfc70-dirty");
    expect(commit?.url).toBe(
      "https://github.com/pschmitt/stricknani/commit/9a7bfc70",
    );
  });

  it("cuts a long SHA to eight characters", () => {
    const sha = "9a7bfc70abcdef0123456789abcdef0123456789";
    expect(parseCommit(sha)?.display).toBe("9a7bfc70");
    expect(parseCommit(sha)?.url.endsWith(sha)).toBe(true);
  });

  it("has no commit for blank, unknown or malformed input", () => {
    expect(parseCommit("")).toBeNull();
    expect(parseCommit(null)).toBeNull();
    expect(parseCommit("unknown")).toBeNull();
    expect(parseCommit("not a sha")).toBeNull();
  });

  it("adds the commit to the bug report", () => {
    expect(
      versionReport(emptyServerBuild, "v1.0.0", parseCommit("9a7bfc70")),
    ).toContain("Commit: 9a7bfc70");
  });
});
