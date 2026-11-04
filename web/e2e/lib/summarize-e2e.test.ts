// Tests for bounded, redacted Android instrumentation summaries.
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  MAX_FILE_BYTES,
  MAX_OUTPUT_LINES,
  loadRedactor,
  summarizeResults,
} from "./summarize-e2e";

let tmp: string;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), "summarize-e2e-"));
});

afterEach(() => {
  rmSync(tmp, { recursive: true, force: true });
});

function writePrivate(path: string, value: unknown): void {
  writeFileSync(path, JSON.stringify(value));
  chmodSync(path, 0o600);
}

function escapeXml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#x27;");
}

describe("summarizeResults", () => {
  it("shows the failure and short frames without fixture values", () => {
    const fixtureEmail = "fixture" + "@" + "example.invalid";
    const fixturePassword = "fixture" + "-pass";
    const fixtureToken = "sna_" + "ab".repeat(10);
    const fixtureUrl = "http://127.0.0.1:4321";
    const reportUrl = "https://127.0.0.1:4321/api/v1/yarns?page=1";
    const failureMessage =
      "Could not load Riverbend Merino DK for " +
      fixtureEmail +
      "; " +
      "pass" +
      "word=" +
      fixturePassword +
      " " +
      "Bear" +
      "er " +
      fixtureToken +
      " while fetching " +
      reportUrl;
    const results = join(tmp, "results");
    mkdirSync(results);
    writeFileSync(
      join(results, "TEST-android.xml"),
      `<testsuite tests="1" failures="1">
        <testcase classname="blue.anika.wolle.e2e.StricknaniSmokeTest"
          name="onboardingSyncDetailAndSettings">
          <failure type="java.lang.AssertionError"
            message="${escapeXml(failureMessage)}">
            java.lang.AssertionError: fetch ${reportUrl}
              at blue.anika.wolle.SomeScreen.load(SomeScreen.kt:42)
              at blue.anika.wolle.MainActivity.onCreate(MainActivity.kt:19)
          </failure>
        </testcase></testsuite>`,
    );
    const fixtureFile = join(tmp, "fixture.json");
    const tokenFile = join(tmp, "token.json");
    writePrivate(fixtureFile, {
      url: fixtureUrl,
      email: fixtureEmail,
      password: fixturePassword,
    });
    writePrivate(tokenFile, { token: fixtureToken });

    const output = summarizeResults(
      results,
      loadRedactor(fixtureFile, tokenFile),
    ).join("\n");

    expect(output).toContain(
      "StricknaniSmokeTest#onboardingSyncDetailAndSettings",
    );
    expect(output).toContain("java.lang.AssertionError");
    expect(output).toContain("SomeScreen.load(SomeScreen.kt:42)");
    expect(output).toContain("[redacted]");
    expect(output).toContain("[redacted-url]");
    expect(output).not.toContain(fixtureEmail);
    expect(output).not.toContain(fixturePassword);
    expect(output).not.toContain(fixtureToken);
    expect(output).not.toContain("Riverbend Merino DK");
    expect(output).not.toContain("127.0.0.1:4321");
  });

  it("keeps the app callsite after framework frames and redacts it", () => {
    const email = "offline" + "@example.invalid";
    const password = "private-fixture-password";
    const token = "sna_" + "cd".repeat(12);
    const fixtureFile = join(tmp, "fixture.json");
    writePrivate(fixtureFile, {
      url: "http://127.0.0.1:4321",
      email,
      password,
    });
    const tokenFile = join(tmp, "token.json");
    writePrivate(tokenFile, { token });
    const results = join(tmp, "results");
    mkdirSync(results);
    const appFrame =
      "at blue.anika.wolle.sync.SyncWorker.retry(SyncWorker.kt:55) " +
      `email=${email} password=${password} Bearer ${token}`;
    writeFileSync(
      join(results, "TEST-app-frame.xml"),
      `<testsuite tests="1" failures="1">
        <testcase classname="blue.anika.wolle.e2e.FullJourney" name="offlineRetry">
          <failure type="ComposeTimeout" message="timed out">
            at androidx.compose.ui.test.Compose.wait(Compose.kt:10)
            at org.junit.runners.ParentRunner.run(ParentRunner.java:20)
            ${escapeXml(appFrame)}
            at blue.anika.wolle.sync.SyncWorker.execute(SyncWorker.kt:60)
          </failure>
        </testcase></testsuite>`,
    );

    const output = summarizeResults(
      results,
      loadRedactor(fixtureFile, tokenFile),
    ).join("\n");

    expect(output).toContain("Compose.wait(Compose.kt:10)");
    expect(output).toContain("ParentRunner.run(ParentRunner.java:20)");
    expect(output).toContain("SyncWorker.retry(SyncWorker.kt:55)");
    expect(output).not.toContain(email);
    expect(output).not.toContain(password);
    expect(output).not.toContain(token);
    expect(output.split("  at ").length - 1).toBe(3);
  });

  it("emits only a constant message when reports are missing", () => {
    expect(summarizeResults(join(tmp, "missing"))).toEqual([
      "No Android instrumentation XML report was found.",
    ]);
  });

  it("discovers a nested device report", () => {
    const reportDir = join(tmp, "results", "device-id");
    mkdirSync(reportDir, { recursive: true });
    writeFileSync(
      join(reportDir, "TEST-nested.xml"),
      '<testsuite tests="1" failures="1">' +
        '<testcase classname="NestedDeviceTest" name="nestedFailure">' +
        '<failure type="AssertionError" message="expected the fixture">' +
        "  at NestedDeviceTest.run(NestedDeviceTest.kt:7)" +
        "</failure></testcase></testsuite>",
    );

    const output = summarizeResults(join(tmp, "results")).join("\n");

    expect(output).toContain("NestedDeviceTest#nestedFailure");
    expect(output).toContain("NestedDeviceTest.kt:7");
  });

  it("ignores a symlinked report", () => {
    const results = join(tmp, "results");
    mkdirSync(results);
    const outsideReport = join(tmp, "TEST-outside.xml");
    writeFileSync(
      outsideReport,
      '<testsuite tests="1" failures="1"><testcase name="outside">' +
        '<failure message="outside report value" /></testcase></testsuite>',
    );
    symlinkSync(outsideReport, join(results, "TEST-symlink.xml"));

    const output = summarizeResults(results).join("\n");

    expect(output).toContain(
      "No Android instrumentation XML report was found.",
    );
    expect(output).not.toContain("outside report value");
  });

  it("never echoes the contents of a malformed report", () => {
    const results = join(tmp, "results");
    mkdirSync(results);
    writeFileSync(
      join(results, "TEST-broken.xml"),
      "not xml containing sna_" + "f".repeat(64),
    );

    const output = summarizeResults(results).join("\n");

    expect(output).toContain("could not be parsed");
    expect(output).not.toContain("sna_");
    expect(output).not.toContain("not xml");
  });

  it("does not read or echo an oversized report", () => {
    const results = join(tmp, "results");
    mkdirSync(results);
    writeFileSync(
      join(results, "TEST-large.xml"),
      "fixture-pass " + "x".repeat(MAX_FILE_BYTES),
    );

    const output = summarizeResults(results).join("\n");

    expect(output).toContain("exceeded the diagnostic size limit");
    expect(output).not.toContain("fixture-pass");
  });

  it("caps the output for many failing cases", () => {
    const results = join(tmp, "results");
    mkdirSync(results);
    const cases = Array.from(
      { length: 100 },
      (_, index) =>
        `<testcase classname="Example" name="test${index}">` +
        '<failure type="AssertionError" message="expected a value" />' +
        "</testcase>",
    ).join("");
    writeFileSync(
      join(results, "TEST-many.xml"),
      `<testsuite tests="100" failures="100">${cases}</testsuite>`,
    );

    const output = summarizeResults(results);

    expect(output.length).toBeLessThanOrEqual(MAX_OUTPUT_LINES);
    expect(output[0]).toContain("100 failures/errors");
    expect(
      output.some((line) =>
        line.includes("additional failed test cases omitted"),
      ),
    ).toBe(true);
  });
});
