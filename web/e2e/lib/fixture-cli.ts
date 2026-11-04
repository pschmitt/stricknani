/**
 * Command line entry for the disposable Rust server fixture (port of the
 * `main()` of ci/stricknani/rust_fixture.py).
 *
 *   npm --prefix web run fixture -- start --binary B --temp-dir D \
 *     --fixture-file F --state-file S [--web-root W] [--server-binary X] \
 *     [--ocr-mode success|failure] [--archive-mode success|failure] \
 *     [--signup-enabled]
 *   npm --prefix web run fixture -- stop --state-file S
 *   npm --prefix web run fixture -- run-replay --fixture-file F --adb ADB \
 *     --serial SERIAL --apk APK --test-apk TEST_APK [--timeout 300]
 *
 * The extensionless imports of the e2e sources are not understood by Node's
 * native type stripping, so the `fixture` npm script runs this file through
 * `e2e/lib/run-ts.mjs` (Vite module runner), which calls the exported `main`.
 */
import { resolve } from "node:path";
import { parseArgs } from "node:util";
import {
  FixtureError,
  loadFixtureEnvironment,
  startFixture,
  stopFixture,
} from "./fixture";

class UsageError extends Error {}

function parse<T extends Record<string, { type: "string" | "boolean" }>>(
  args: string[],
  options: T,
) {
  try {
    return parseArgs({ args, options, strict: true, allowPositionals: false })
      .values;
  } catch (error) {
    throw new UsageError(
      error instanceof Error ? error.message : "bad arguments",
    );
  }
}

function required(values: Record<string, unknown>, ...names: string[]): void {
  for (const name of names) {
    if (typeof values[name] !== "string" || values[name] === "") {
      throw new UsageError(`the following arguments are required: --${name}`);
    }
  }
}

function choice<T extends string>(
  value: unknown,
  name: string,
  allowed: readonly T[],
  fallback: T,
): T {
  if (value === undefined) return fallback;
  const found = allowed.find((item) => item === value);
  if (found === undefined) {
    throw new UsageError(`argument --${name}: invalid choice`);
  }
  return found;
}

async function runReplayFromFile(values: {
  fixtureFile: string;
  adb: string;
  serial: string;
  apk: string;
  testApk: string;
  timeoutSeconds: number;
}): Promise<void> {
  // Loaded lazily so start/stop do not depend on the replay runner's modules.
  const { readFixture, runReplay } = await import("./replay");
  const fixture = readFixture({
    ...loadFixtureEnvironment(values.fixtureFile),
  });
  await runReplay({
    adb: values.adb,
    serial: values.serial,
    apk: resolve(values.apk),
    testApk: resolve(values.testApk),
    fixture,
    timeoutSeconds: values.timeoutSeconds,
  });
}

export async function main(argv: string[]): Promise<number> {
  const [command, ...rest] = argv;
  try {
    if (command === "start") {
      const v = parse(rest, {
        binary: { type: "string" },
        "temp-dir": { type: "string" },
        "fixture-file": { type: "string" },
        "state-file": { type: "string" },
        "web-root": { type: "string" },
        "server-binary": { type: "string" },
        "ocr-mode": { type: "string" },
        "archive-mode": { type: "string" },
        "signup-enabled": { type: "boolean" },
      });
      required(v, "binary", "temp-dir", "fixture-file", "state-file");
      await startFixture({
        binary: v.binary as string,
        tempDir: v["temp-dir"] as string,
        fixtureFile: v["fixture-file"] as string,
        stateFile: v["state-file"] as string,
        webRoot: v["web-root"],
        serverBinary: v["server-binary"],
        ocrMode: choice(
          v["ocr-mode"],
          "ocr-mode",
          ["success", "failure"] as const,
          "success",
        ),
        archiveMode: choice(
          v["archive-mode"],
          "archive-mode",
          ["success", "failure"] as const,
          "success",
        ),
        signupEnabled: v["signup-enabled"] ?? false,
      });
      console.log("Rust replay fixture started.");
    } else if (command === "stop") {
      const v = parse(rest, { "state-file": { type: "string" } });
      required(v, "state-file");
      await stopFixture(v["state-file"] as string);
      console.log("Rust replay fixture stopped.");
    } else if (command === "run-replay") {
      const v = parse(rest, {
        "fixture-file": { type: "string" },
        adb: { type: "string" },
        serial: { type: "string" },
        apk: { type: "string" },
        "test-apk": { type: "string" },
        timeout: { type: "string" },
      });
      required(v, "fixture-file", "adb", "serial", "apk", "test-apk");
      const timeoutSeconds = Number(v.timeout ?? "300");
      if (!Number.isInteger(timeoutSeconds)) {
        throw new UsageError("argument --timeout: invalid int value");
      }
      if (timeoutSeconds < 1 || timeoutSeconds > 1800) {
        throw new FixtureError(
          "replay timeout must be between 1 and 1800 seconds",
        );
      }
      await runReplayFromFile({
        fixtureFile: v["fixture-file"] as string,
        adb: v.adb as string,
        serial: v.serial as string,
        apk: v.apk as string,
        testApk: v["test-apk"] as string,
        timeoutSeconds,
      });
      console.log("Rust backend replay instrumentation passed.");
    } else {
      throw new UsageError("expected a command: start, stop or run-replay");
    }
  } catch (error) {
    if (error instanceof UsageError) {
      console.error(`usage error: ${error.message}`);
      return 2;
    }
    if (error instanceof FixtureError) {
      console.error(`fixture error: ${error.message}`);
      return 1;
    }
    if (error instanceof Error && error.name === "ReplayError") {
      console.error(`fixture error: ${error.message.slice(0, 4000)}`);
      return 1;
    }
    console.error("fixture error: unexpected replay failure");
    return 1;
  }
  return 0;
}
