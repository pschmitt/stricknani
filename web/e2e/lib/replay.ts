// Run the Rust-backend replay test on one explicitly selected emulator.
//
// Port of ci/stricknani/run_rust_replay.py. The module has no relative
// imports so it can also run directly: `node e2e/lib/replay.ts --adb ...`.
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  closeSync,
  existsSync,
  fstatSync,
  ftruncateSync,
  mkdtempSync,
  openSync,
  readFileSync,
  realpathSync,
  rmSync,
  statSync,
  writeSync,
} from "node:fs";
import { homedir, tmpdir } from "node:os";
import { delimiter, join, sep } from "node:path";
import { pathToFileURL } from "node:url";
import { inspect, parseArgs } from "node:util";

export const APP_ID = "blue.anika.wolle.debug";
export const TEST_COMPONENT =
  "blue.anika.wolle.debug.test/androidx.test.runner.AndroidJUnitRunner";
export const TEST_CLASS =
  "blue.anika.wolle.sync.RustBackendReplayIntegrationTest";
export const FIXTURE_PATH = "files/rust-replay-fixture.json";
export const MAX_DIAGNOSTICS = 4_000;
export const MAX_RAW_LOG_BYTES = 1_000_000;
export const TRUNCATED_LOG_MARKER = Buffer.from(
  "\n[runner: instrumentation log truncated]\n",
);
const TEMP_FIXTURE = "files/.rust-replay-fixture.json.tmp";

/** A safe-to-display runner error with no fixture values attached. */
export class ReplayError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ReplayError";
  }
}

/** Internal: the instrumentation command exceeded its timeout. */
export class InstrumentationTimeout extends Error {
  constructor() {
    super("instrumentation command timed out");
    this.name = "InstrumentationTimeout";
  }
}

export class Fixture {
  readonly url: string;
  readonly sourceUrl: string;
  readonly email: string;
  readonly password: string;
  readonly port: number;

  constructor(
    url: string,
    sourceUrl: string,
    email: string,
    password: string,
    port: number,
  ) {
    this.url = url;
    this.sourceUrl = sourceUrl;
    this.email = email;
    this.password = password;
    this.port = port;
  }

  jsonBytes(): Buffer {
    return Buffer.from(
      JSON.stringify({
        url: this.url,
        email: this.email,
        password: this.password,
      }),
      "utf-8",
    );
  }

  // Never print credentials through console.log / util.inspect.
  [inspect.custom](): string {
    return "Fixture";
  }

  toJSON(): string {
    return "Fixture";
  }
}

function isLoopbackAddress(host: string): boolean {
  const bare =
    host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
  const octets = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(bare);
  if (octets) {
    const parts = octets.slice(1).map(Number);
    return parts.every((part) => part <= 255) && parts[0] === 127;
  }
  return bare === "::1";
}

/** Require a plain loopback HTTP(S) origin suitable for adb reverse. */
export function validateUrl(value: string): [string, number] {
  const invalid = "STRICKNANI_TEST_URL must be a valid loopback URL";
  if (!value || /[\s\u0000-\u001f]/.test(value)) {
    throw new ReplayError(invalid);
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch (error) {
    throw new ReplayError(invalid, { cause: error });
  }
  const scheme = parsed.protocol.replace(/:$/, "");
  if (
    (scheme !== "http" && scheme !== "https") ||
    !parsed.hostname ||
    parsed.username ||
    parsed.password ||
    parsed.hash ||
    parsed.search
  ) {
    throw new ReplayError(invalid);
  }
  const host = parsed.hostname;
  if (host.toLowerCase() !== "localhost" && !isLoopbackAddress(host)) {
    throw new ReplayError("STRICKNANI_TEST_URL must use a loopback host");
  }
  let port: number;
  if (parsed.port === "") {
    port = scheme === "https" ? 443 : 80;
  } else {
    port = Number(parsed.port);
  }
  if (!(port >= 1 && port <= 65535)) {
    throw new ReplayError("STRICKNANI_TEST_URL has an invalid port");
  }
  const path = parsed.pathname.replace(/\/+$/, "");
  return [`${scheme}://127.0.0.1:${port}${path}`, port];
}

/** Read the three documented fixture variables without echoing their values. */
export function readFixture(
  environment: Record<string, string | undefined> = process.env,
): Fixture {
  const url = environment.STRICKNANI_TEST_URL ?? "";
  const email = environment.STRICKNANI_TEST_EMAIL ?? "";
  const password = environment.STRICKNANI_TEST_PASSWORD ?? "";
  if (!url || !email || !password) {
    throw new ReplayError(
      "all STRICKNANI_TEST_URL/EMAIL/PASSWORD variables are required",
    );
  }
  const [normalizedUrl, port] = validateUrl(url);
  if (Buffer.byteLength(email, "utf-8") > 320 || !email.trim()) {
    throw new ReplayError("STRICKNANI_TEST_EMAIL is invalid");
  }
  const passwordBytes = Buffer.byteLength(password, "utf-8");
  if (passwordBytes < 12 || passwordBytes > 1024) {
    throw new ReplayError("STRICKNANI_TEST_PASSWORD must be 12 to 1024 bytes");
  }
  return new Fixture(normalizedUrl, url, email, password, port);
}

/** Remove exact fixture strings and common PAT forms from diagnostics. */
export function redact(input: string, fixture: Fixture): string {
  let text = input;
  const values = [
    fixture.password,
    fixture.email,
    fixture.url,
    fixture.sourceUrl,
  ].sort((a, b) => b.length - a.length);
  for (const value of values) {
    if (value) {
      text = text.split(value).join("[redacted]");
    }
  }
  text = text.replace(/sna_[A-Za-z0-9_-]{8,}/g, "[redacted-token]");
  text = text.replace(/Bearer\s+\S+/gi, "Bearer [redacted-token]");
  return text.slice(-MAX_DIAGNOSTICS);
}

interface RunOptions {
  timeout: number;
  inputBytes?: Buffer;
  discardStdout?: boolean;
}

function run(command: readonly string[], options: RunOptions): Buffer {
  const [program, ...args] = command;
  if (program === undefined) {
    throw new ReplayError("could not execute the Android replay command");
  }
  const result = spawnSync(program, args, {
    input: options.inputBytes,
    stdio: [
      options.inputBytes === undefined ? "ignore" : "pipe",
      options.discardStdout ? "ignore" : "pipe",
      "pipe",
    ],
    timeout: options.timeout * 1000,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) {
    const code = (result.error as NodeJS.ErrnoException).code;
    if (code === "ETIMEDOUT") {
      throw new ReplayError("Android replay command timed out", {
        cause: result.error,
      });
    }
    throw new ReplayError("could not execute the Android replay command", {
      cause: result.error,
    });
  }
  if (result.status !== 0) {
    // stderr is deliberately not exposed here; callers can choose to provide a
    // separately redacted bounded diagnostic from the instrumentation log.
    throw new ReplayError("Android replay command failed");
  }
  return Buffer.from(result.stdout ?? "");
}

function adbCommand(adb: string, serial: string, ...args: string[]): string[] {
  return [adb, "-s", serial, ...args];
}

function tcpPort(value: string): number | null {
  if (!value.startsWith("tcp:")) {
    return null;
  }
  const raw = value.slice("tcp:".length);
  if (!/^\d+$/.test(raw)) {
    return null;
  }
  const port = Number(raw);
  return port >= 1 && port <= 65535 ? port : null;
}

/** Reuse an exact reverse mapping, create an absent one, or reject conflicts. */
export function ensureReverse(
  adb: string,
  serial: string,
  port: number,
  timeout: number,
): boolean {
  const result = run(adbCommand(adb, serial, "reverse", "--list"), { timeout });
  for (const line of result.toString("utf-8").split(/\r?\n/)) {
    let fields = line.split(/\s+/).filter(Boolean);
    // adb scopes --list to the selected transport; its label can be host-N.
    if (fields.length === 3) {
      fields = fields.slice(1);
    }
    if (fields.length !== 2) {
      continue;
    }
    const guestPort = tcpPort(fields[0] ?? "");
    if (guestPort !== port) {
      continue;
    }
    if (tcpPort(fields[1] ?? "") === port) {
      return false;
    }
    throw new ReplayError(
      "selected emulator already has a conflicting reverse mapping",
    );
  }
  run(
    adbCommand(
      adb,
      serial,
      "reverse",
      "--no-rebind",
      `tcp:${port}`,
      `tcp:${port}`,
    ),
    { timeout },
  );
  return true;
}

function isEmulatorSerial(serial: string): boolean {
  return /^emulator-\d+$/.test(serial);
}

export function validateEmulator(
  adb: string,
  serial: string,
  timeout: number,
): void {
  if (!isEmulatorSerial(serial)) {
    throw new ReplayError("--serial must name an emulator-* device");
  }
  const devices = run([adb, "devices", "-l"], { timeout }).toString("utf-8");
  const ready = devices.split(/\r?\n/).some((line) => {
    if (!line.trim()) {
      return false;
    }
    const fields = line.split(/\s+/).filter(Boolean);
    return fields[0] === serial && fields[1] === "device";
  });
  if (!ready) {
    throw new ReplayError("selected emulator is not connected and ready");
  }
  const emulator = run(
    adbCommand(adb, serial, "shell", "getprop", "ro.kernel.qemu"),
    {
      timeout,
    },
  )
    .toString("utf-8")
    .trim();
  if (emulator !== "1") {
    throw new ReplayError(
      "selected Android device does not report emulator mode",
    );
  }
}

export function instrumentationSucceeded(output: string): boolean {
  const expected = `INSTRUMENTATION_STATUS: class=${TEST_CLASS}`;
  if (
    !output.includes(expected) ||
    !output.includes("INSTRUMENTATION_CODE: -1")
  ) {
    return false;
  }
  if (/INSTRUMENTATION_STATUS_CODE:\s*-3\b|\bSKIPPED\b/.test(output)) {
    return false;
  }
  if (
    output.includes("FAILURES!!!") ||
    output.includes("INSTRUMENTATION_FAILED")
  ) {
    return false;
  }
  const summary = /\bOK \((\d+) tests?\)/.exec(output);
  return summary !== null && Number(summary[1]) > 0;
}

/**
 * Run the instrumentation command with stdout and stderr merged into a private
 * log file (mode 0600, exclusive create) and keep at most one megabyte of it.
 * Returns the exit code; throws InstrumentationTimeout on timeout.
 */
export function captureInstrumentation(
  command: readonly string[],
  logPath: string,
  timeout: number,
): number {
  const [program, ...args] = command;
  if (program === undefined) {
    throw new ReplayError("could not start instrumentation command");
  }
  const fd = openSync(logPath, "wx", 0o600);
  try {
    const result = spawnSync(program, args, {
      stdio: ["ignore", fd, fd],
      timeout: timeout * 1000,
    });
    if (result.error) {
      if ((result.error as NodeJS.ErrnoException).code === "ETIMEDOUT") {
        throw new InstrumentationTimeout();
      }
      throw new ReplayError("could not start instrumentation command", {
        cause: result.error,
      });
    }
    if (fstatSync(fd).size > MAX_RAW_LOG_BYTES) {
      ftruncateSync(fd, MAX_RAW_LOG_BYTES);
      writeSync(
        fd,
        TRUNCATED_LOG_MARKER,
        0,
        TRUNCATED_LOG_MARKER.length,
        MAX_RAW_LOG_BYTES,
      );
    }
    return result.status ?? -1;
  } finally {
    closeSync(fd);
  }
}

function safePath(raw: string, label: string): string {
  const expanded =
    raw === "~" || raw.startsWith("~/") ? join(homedir(), raw.slice(1)) : raw;
  let isFile = false;
  try {
    isFile = statSync(expanded).isFile();
  } catch {
    isFile = false;
  }
  if (!isFile) {
    throw new ReplayError(`${label} must point to an existing APK file`);
  }
  return realpathSync(expanded);
}

export interface ReplayOptions {
  adb: string;
  serial: string;
  apk: string;
  testApk: string;
  fixture: Fixture;
  timeoutSeconds: number;
}

export function runReplay(options: ReplayOptions): void {
  const { adb, serial, apk, testApk, fixture } = options;
  const timeout = options.timeoutSeconds;
  const shell = (...args: string[]): string[] =>
    adbCommand(adb, serial, "shell", "run-as", APP_ID, ...args);
  // Device checks deliberately precede package installation or fixture writes.
  validateEmulator(adb, serial, timeout);
  const reverseCreated = ensureReverse(adb, serial, fixture.port, timeout);
  let provisionStarted = false;
  try {
    run(adbCommand(adb, serial, "install", "-r", apk), { timeout });
    run(adbCommand(adb, serial, "install", "-r", "-t", testApk), { timeout });
    run(shell("mkdir", "-p", "files"), { timeout });
    run(shell("chmod", "700", "files"), { timeout });
    provisionStarted = true;
    run(shell("toybox", "tee", TEMP_FIXTURE), {
      timeout,
      inputBytes: fixture.jsonBytes(),
      discardStdout: true,
    });
    run(shell("chmod", "600", TEMP_FIXTURE), { timeout });
    run(shell("mv", "-f", TEMP_FIXTURE, FIXTURE_PATH), { timeout });
    run(shell("chmod", "600", FIXTURE_PATH), { timeout });
    const tempDir = mkdtempSync(join(tmpdir(), "stricknani-replay-"));
    try {
      chmodSync(tempDir, 0o700);
      const logPath = join(tempDir, "instrumentation.log");
      const instrumentationCommand = adbCommand(
        adb,
        serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        TEST_CLASS,
        TEST_COMPONENT,
      );
      let returnCode: number;
      try {
        returnCode = captureInstrumentation(
          instrumentationCommand,
          logPath,
          timeout,
        );
      } catch (error) {
        if (error instanceof InstrumentationTimeout) {
          try {
            run(adbCommand(adb, serial, "shell", "am", "force-stop", APP_ID), {
              timeout,
            });
          } catch (stopError) {
            if (!(stopError instanceof ReplayError)) {
              throw stopError;
            }
          }
          throw new ReplayError("Rust replay instrumentation timed out", {
            cause: error,
          });
        }
        throw error;
      }
      const raw = readFileSync(logPath, "utf-8");
      if (returnCode !== 0) {
        throw new ReplayError(redact(raw, fixture) || "instrumentation failed");
      }
      if (!instrumentationSucceeded(raw)) {
        throw new ReplayError(
          redact(raw, fixture) || "instrumentation did not pass",
        );
      }
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  } finally {
    // Remove only the private fixture written by this runner and its exact
    // reverse mapping. No app data reset or package uninstall is performed.
    if (provisionStarted) {
      try {
        run(shell("rm", "-f", FIXTURE_PATH, TEMP_FIXTURE), { timeout });
      } catch (error) {
        if (!(error instanceof ReplayError)) {
          throw error;
        }
      }
    }
    if (reverseCreated) {
      try {
        run(
          adbCommand(adb, serial, "reverse", "--remove", `tcp:${fixture.port}`),
          {
            timeout,
          },
        );
      } catch (error) {
        if (!(error instanceof ReplayError)) {
          throw error;
        }
      }
    }
  }
}

function which(
  program: string,
  environment: Record<string, string | undefined>,
): string | null {
  for (const directory of (environment.PATH ?? "").split(delimiter)) {
    if (!directory) {
      continue;
    }
    const candidate = join(directory, program);
    try {
      if (statSync(candidate).isFile()) {
        return candidate;
      }
    } catch {
      continue;
    }
  }
  return null;
}

/** Command-line entry; returns the process exit code. */
export function main(
  argv: readonly string[] = process.argv.slice(2),
  environment: Record<string, string | undefined> = process.env,
  stderr: (line: string) => void = (line) => process.stderr.write(`${line}\n`),
  stdout: (line: string) => void = (line) => process.stdout.write(`${line}\n`),
): number {
  let values: {
    adb?: string;
    serial?: string;
    apk?: string;
    "test-apk"?: string;
    timeout?: string;
  };
  try {
    values = parseArgs({
      args: [...argv],
      options: {
        adb: { type: "string" },
        serial: { type: "string" },
        apk: { type: "string" },
        "test-apk": { type: "string" },
        timeout: { type: "string", default: "300" },
      },
      strict: true,
    }).values;
  } catch {
    stderr("runner error: invalid arguments");
    return 2;
  }
  if (!values.adb || !values.serial || !values.apk || !values["test-apk"]) {
    stderr("runner error: --adb, --serial, --apk and --test-apk are required");
    return 2;
  }
  const timeout = /^\d+$/.test(values.timeout ?? "")
    ? Number(values.timeout)
    : 0;
  if (timeout <= 0 || timeout > 1800) {
    stderr("runner error: --timeout must be between 1 and 1800 seconds");
    return 2;
  }
  const adb = values.adb.includes(sep)
    ? values.adb
    : which(values.adb, environment);
  if (!adb || !existsSync(adb) || !statSync(adb).isFile()) {
    stderr("runner error: --adb must point to an executable");
    return 2;
  }
  try {
    const apk = safePath(values.apk, "--apk");
    const testApk = safePath(values["test-apk"], "--test-apk");
    // Reject obviously non-emulator targets before even loading credentials.
    if (!isEmulatorSerial(values.serial)) {
      throw new ReplayError("--serial must name an emulator-* device");
    }
    const fixture = readFixture(environment);
    runReplay({
      adb,
      serial: values.serial,
      apk,
      testApk,
      fixture,
      timeoutSeconds: timeout,
    });
  } catch (error) {
    if (error instanceof ReplayError) {
      stderr(`runner error: ${error.message.slice(0, MAX_DIAGNOSTICS)}`);
      return 1;
    }
    throw error;
  }
  stdout("Rust backend replay instrumentation passed.");
  return 0;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  process.exitCode = main();
}
