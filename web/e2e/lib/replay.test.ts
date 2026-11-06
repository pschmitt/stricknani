// Safety and orchestration tests for the opt-in Android replay runner.
import * as childProcess from "node:child_process";
import {
  mkdtempSync,
  rmSync,
  statSync,
  writeFileSync,
  writeSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  APP_ID,
  MAX_DIAGNOSTICS,
  MAX_RAW_LOG_BYTES,
  ReplayError,
  TEST_CLASS,
  TEST_COMPONENT,
  TRUNCATED_LOG_MARKER,
  captureInstrumentation,
  ensureReverse,
  main,
  readFixture,
  redact,
  runReplay,
  validateEmulator,
  validateUrl,
} from "./replay";

vi.mock("node:child_process", async (importOriginal) => {
  const actual = await importOriginal<typeof import("node:child_process")>();
  return { ...actual, spawnSync: vi.fn(actual.spawnSync) };
});

const spawnSync = vi.mocked(childProcess.spawnSync);
const realSpawnSync = (
  await vi.importActual<typeof import("node:child_process")>(
    "node:child_process",
  )
).spawnSync;

type SpawnArgs = Parameters<typeof childProcess.spawnSync>;

interface Call {
  command: string[];
  options: Record<string, unknown>;
}

function fixtureValues(url = "http://localhost:7674"): Record<string, string> {
  return {
    STRICKNANI_TEST_URL: url,
    STRICKNANI_TEST_EMAIL: "replay@example.invalid",
    STRICKNANI_TEST_PASSWORD: "disposable-test-password",
  };
}

function result(stdout = "", status = 0): ReturnType<typeof realSpawnSync> {
  return {
    pid: 1,
    output: [null, Buffer.from(stdout), Buffer.alloc(0)],
    stdout: Buffer.from(stdout),
    stderr: Buffer.alloc(0),
    status,
    signal: null,
  };
}

let calls: Call[];
let tempDir: string;

/** Install a fake spawnSync; `instrumentation` is written to the log fd. */
function fakeSpawn(
  handler: (command: string[]) => string,
  instrumentation: { log: string; status: number } | "timeout" = {
    log: "",
    status: 0,
  },
): void {
  spawnSync.mockImplementation(((...args: SpawnArgs) => {
    const [program, rawArgs, options] = args;
    const command = [program, ...((rawArgs as string[] | undefined) ?? [])];
    calls.push({
      command,
      options: (options ?? {}) as Record<string, unknown>,
    });
    if (command.includes("instrument")) {
      if (instrumentation === "timeout") {
        return {
          ...result(),
          status: null,
          error: Object.assign(new Error("spawnSync ETIMEDOUT"), {
            code: "ETIMEDOUT",
          }),
        };
      }
      const stdio = (options as { stdio: unknown[] }).stdio;
      writeSync(stdio[1] as number, instrumentation.log);
      return result("", instrumentation.status);
    }
    return result(handler(command));
  }) as unknown as typeof childProcess.spawnSync);
}

function emulatorHandler(command: string[]): string {
  if (command.includes("devices")) {
    return "List of devices attached\nemulator-5554 device\n";
  }
  if (command.includes("getprop")) {
    return "1\n";
  }
  return "";
}

function apks(): { apk: string; testApk: string } {
  const apk = join(tempDir, "app-x86_64-debug.apk");
  const testApk = join(tempDir, "app-debug-androidTest.apk");
  writeFileSync(apk, "");
  writeFileSync(testApk, "");
  return { apk, testApk };
}

beforeEach(() => {
  calls = [];
  tempDir = mkdtempSync(join(tmpdir(), "replay-test-"));
});

afterEach(() => {
  spawnSync.mockReset();
  spawnSync.mockImplementation(realSpawnSync as typeof childProcess.spawnSync);
  rmSync(tempDir, { recursive: true, force: true });
});

describe("validateUrl", () => {
  it("only allows plain loopback URLs", () => {
    expect(validateUrl("http://localhost:7674/api/root/")).toEqual([
      "http://127.0.0.1:7674/api/root",
      7674,
    ]);
    expect(validateUrl("https://[::1]:8443")[1]).toBe(8443);

    for (const url of [
      "https://example.invalid",
      "http://127.0.0.1:7674/#fragment",
      "http://user:pass@localhost:7674",
      "http://localhost:7674/?token=x",
    ]) {
      expect(() => validateUrl(url)).toThrow(ReplayError);
    }
  });
});

describe("main", () => {
  it("rejects an invalid serial before subprocesses or credentials", () => {
    const { apk, testApk } = apks();
    const adb = join(tempDir, "adb");
    writeFileSync(adb, "");
    fakeSpawn(() => {
      throw new Error("adb called");
    });
    const errors: string[] = [];

    const status = main(
      [
        "--adb",
        adb,
        "--serial",
        "physical-123",
        "--apk",
        apk,
        "--test-apk",
        testApk,
      ],
      {},
      (line) => errors.push(line),
    );

    expect(status).toBe(1);
    expect(errors.join("\n")).toContain("emulator-*");
    expect(errors.join("\n")).not.toContain("disposable-test-password");
    expect(spawnSync).not.toHaveBeenCalled();
  });
});

describe("validateEmulator", () => {
  it("checks the qemu property before install", () => {
    fakeSpawn((command) =>
      command.includes("devices") ? "emulator-5554 device\n" : "0\n",
    );
    expect(() => validateEmulator("/usr/bin/adb", "emulator-5554", 10)).toThrow(
      /emulator mode/,
    );
    expect(calls.some((call) => call.command.includes("install"))).toBe(false);
  });
});

describe("ensureReverse", () => {
  it("reuses an existing mapping without removal", () => {
    fakeSpawn(() => "host-19 tcp:7674 tcp:7674\n");
    expect(ensureReverse("adb", "emulator-5554", 7674, 10)).toBe(false);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.command.slice(-2)).toEqual(["reverse", "--list"]);
  });

  it("fails closed on a conflicting mapping", () => {
    fakeSpawn(() => "emulator-5554 tcp:7674 tcp:8080\n");
    expect(() => ensureReverse("adb", "emulator-5554", 7674, 10)).toThrow(
      /conflicting reverse/,
    );
    expect(calls).toHaveLength(1);
  });
});

describe("runReplay", () => {
  it("streams the fixture to a private file and runs only the target class", () => {
    const { apk, testApk } = apks();
    fakeSpawn(emulatorHandler, {
      log:
        `INSTRUMENTATION_STATUS: class=${TEST_CLASS}\n` +
        "INSTRUMENTATION_STATUS_CODE: 0\n" +
        "INSTRUMENTATION_CODE: -1\nOK (1 test)\n",
      status: 0,
    });
    const fixture = readFixture(fixtureValues());

    runReplay({
      adb: "/usr/bin/adb",
      serial: "emulator-5554",
      apk,
      testApk,
      fixture,
      timeoutSeconds: 10,
    });

    const provision = calls.find((call) =>
      call.command
        .slice(-3)
        .join(" ")
        .endsWith("toybox tee files/.rust-replay-fixture.json.tmp"),
    );
    expect(provision).toBeDefined();
    expect(provision?.options.input).toEqual(fixture.jsonBytes());
    expect((provision?.options.stdio as string[])[1]).toBe("ignore");
    expect(provision?.command.join(" ")).not.toContain(fixture.password);

    const instrumentation = calls.find((call) =>
      call.command.includes("instrument"),
    );
    expect(instrumentation?.command.slice(-4)).toEqual([
      "-e",
      "class",
      TEST_CLASS,
      TEST_COMPONENT,
    ]);
    expect(
      calls.filter((call) => call.command.includes("install")),
    ).toHaveLength(2);
    expect(calls.some((call) => call.command.includes("-t"))).toBe(true);
    expect(calls.some((call) => call.command.includes("--remove"))).toBe(true);
    expect(
      calls.some((call) =>
        call.command.join(" ").includes("rm -f files/rust-replay-fixture.json"),
      ),
    ).toBe(true);
    expect(
      calls.some(
        (call) => call.command.includes("sh") || call.command.includes("-c"),
      ),
    ).toBe(false);
  });

  it.each([
    "INSTRUMENTATION_STATUS_CODE: -3\nSKIPPED\n",
    "FAILURES!!!\nINSTRUMENTATION_FAILED\n",
    "INSTRUMENTATION_CODE: -1\n",
  ])("does not treat %j as a pass", (output) => {
    const { apk, testApk } = apks();
    fakeSpawn(emulatorHandler, { log: output, status: 0 });
    expect(() =>
      runReplay({
        adb: "adb",
        serial: "emulator-5554",
        apk,
        testApk,
        fixture: readFixture(fixtureValues()),
        timeoutSeconds: 10,
      }),
    ).toThrow(ReplayError);
  });

  it("redacts secret output of a failed instrumentation", () => {
    const { apk, testApk } = apks();
    fakeSpawn(emulatorHandler, {
      log:
        "FAILURES!!! replay@example.invalid disposable-test-password " +
        "sna_abcdefghijklmno",
      status: 1,
    });
    let message = "";
    try {
      runReplay({
        adb: "adb",
        serial: "emulator-5554",
        apk,
        testApk,
        fixture: readFixture(fixtureValues()),
        timeoutSeconds: 10,
      });
    } catch (error) {
      message = (error as Error).message;
    }
    expect(message).toContain("FAILURES!!!");
    expect(message).not.toContain("replay@example.invalid");
    expect(message).not.toContain("disposable-test-password");
    expect(message).not.toContain("sna_abcdefghijklmno");
  });

  it("force-stops the app and cleans runner state on timeout", () => {
    const { apk, testApk } = apks();
    fakeSpawn(emulatorHandler, "timeout");
    expect(() =>
      runReplay({
        adb: "adb",
        serial: "emulator-5554",
        apk,
        testApk,
        fixture: readFixture(fixtureValues()),
        timeoutSeconds: 1,
      }),
    ).toThrow(/timed out/);
    expect(
      calls.some(
        (call) =>
          call.command.includes("force-stop") && call.command.includes(APP_ID),
      ),
    ).toBe(true);
    expect(calls.some((call) => call.command.includes("--remove"))).toBe(true);
    expect(
      calls.some(
        (call) => call.command.at(-1) === "files/.rust-replay-fixture.json.tmp",
      ),
    ).toBe(true);
  });
});

describe("redact", () => {
  it("removes credentials and PATs and bounds the output", () => {
    const fixture = readFixture(fixtureValues());
    const diagnostic = redact(
      "failed for replay@example.invalid disposable-test-password " +
        "sna_abcdefghijklmno",
      fixture,
    );
    expect(diagnostic).not.toContain("replay@example.invalid");
    expect(diagnostic).not.toContain("disposable-test-password");
    expect(diagnostic).not.toContain("sna_abcdefghijklmno");
    expect(redact("x".repeat(10_000), fixture).length).toBeLessThanOrEqual(
      MAX_DIAGNOSTICS,
    );
  });
});

describe("captureInstrumentation", () => {
  it("keeps a private, bounded log", () => {
    const logPath = join(tempDir, "raw.log");
    const payload = MAX_RAW_LOG_BYTES + 100;
    const script = `process.stdout.write("x".repeat(${payload}))`;

    expect(
      captureInstrumentation([process.execPath, "-e", script], logPath, 20),
    ).toBe(0);

    const info = statSync(logPath);
    expect(info.mode & 0o777).toBe(0o600);
    expect(info.size).toBe(MAX_RAW_LOG_BYTES + TRUNCATED_LOG_MARKER.length);
  });
});
