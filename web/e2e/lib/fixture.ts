/**
 * Manage a private, disposable Rust server fixture (port of
 * ci/stricknani/rust_fixture.py).
 *
 * Fixture files and runtime directories are private (0600/0700), live outside
 * the checkout and never print credentials. Spawned servers get their own
 * process group and are only signalled when their /proc start time and the
 * private owner marker still match the recorded state.
 */
import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import {
  accessSync,
  closeSync,
  constants,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  rmSync,
  statSync,
  writeSync,
} from "node:fs";
import { createServer } from "node:net";
import { homedir } from "node:os";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT: string = realpathSync(resolve(HERE, "../../.."));
export const OWNER_FILE = ".rust-fixture-owner";
const BROWSER_AUTHORITY =
  /^stricknani-device-[a-f0-9]{12}\.localhost:([1-9][0-9]{0,4})$/;

/** A safe-to-display fixture error that does not contain credentials. */
export class FixtureError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FixtureError";
  }
}

export interface FixtureEnvironment {
  STRICKNANI_TEST_URL: string;
  STRICKNANI_TEST_EMAIL: string;
  STRICKNANI_TEST_PASSWORD: string;
}

export interface FixtureProcess {
  pid: number | undefined;
  /** True once the process exited (or could not be spawned). */
  exited(): boolean;
}

/**
 * Seams for unit tests. Production code always goes through this object, so
 * tests can replace a single entry (mirrors the Python monkeypatching).
 */
export const fixtureDeps = {
  spawnSync,
  spawnServer(
    argv: string[],
    env: Record<string, string>,
    logFd: number,
  ): FixtureProcess {
    const [command, ...args] = argv;
    if (command === undefined) throw new FixtureError("empty server command");
    let exited = false;
    const child = spawn(command, args, {
      env,
      stdio: ["ignore", logFd, logFd],
      detached: true, // own session and process group, like start_new_session
    });
    child.on("exit", () => {
      exited = true;
    });
    child.on("error", () => {
      exited = true;
    });
    child.unref();
    return { pid: child.pid, exited: () => exited };
  },
  unusedPort,
  processStartTime,
  safeTerminate,
  waitForHealth,
  runCmd,
};

function isInside(parent: string, child: string): boolean {
  return child === parent || child.startsWith(parent + sep);
}

/** `realpath` that tolerates a missing tail (like Python's non-strict resolve). */
function resolveNonStrict(path: string): string {
  let expanded = path;
  if (expanded === "~") expanded = homedir();
  else if (expanded.startsWith("~/"))
    expanded = join(homedir(), expanded.slice(2));
  const absolute = resolve(expanded);
  const tail: string[] = [];
  let current = absolute;
  for (;;) {
    try {
      const real = realpathSync(current);
      return tail.length ? join(real, ...tail.reverse()) : real;
    } catch {
      const parent = dirname(current);
      if (parent === current) return absolute;
      tail.push(current.slice(parent.length).replace(/^[\\/]+/, ""));
      current = parent;
    }
  }
}

export function outsideRepository(path: string, label: string): string {
  const resolved = resolveNonStrict(path);
  if (isInside(REPO_ROOT, resolved)) {
    throw new FixtureError(`${label} must be outside the repository`);
  }
  return resolved;
}

function privateWrite(path: string, data: Buffer): void {
  const fd = openSync(
    path,
    constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
    0o600,
  );
  try {
    writeSync(fd, data);
  } catch (error) {
    closeSync(fd);
    rmSync(path, { force: true });
    throw error;
  }
  closeSync(fd);
}

/** Write JSON to a new private file outside the checkout. */
export function writePrivateJson(
  path: string,
  value: Record<string, unknown>,
): void {
  const target = outsideRepository(path, "fixture file");
  mkdirSync(dirname(target), { mode: 0o700, recursive: true });
  privateWrite(target, Buffer.from(JSON.stringify(value), "utf-8"));
}

/** Load fixture values from a mode-0600 JSON file without changing env. */
export function loadFixtureEnvironment(path: string): FixtureEnvironment {
  const target = outsideRepository(path, "fixture file");
  let values: unknown;
  try {
    const info = statSync(target);
    if (!info.isFile() || (info.mode & 0o077) !== 0) {
      throw new FixtureError("fixture file must be a private regular file");
    }
    values = JSON.parse(readFileSync(target, "utf-8"));
  } catch (error) {
    if (error instanceof FixtureError) throw error;
    throw new FixtureError("could not read the private fixture file");
  }
  if (typeof values !== "object" || values === null || Array.isArray(values)) {
    throw new FixtureError("fixture file has an invalid format");
  }
  const record = values as Record<string, unknown>;
  const { url, email, password } = record;
  if (
    typeof url !== "string" ||
    typeof email !== "string" ||
    typeof password !== "string" ||
    !url ||
    !email ||
    !password
  ) {
    throw new FixtureError("fixture file has an invalid format");
  }
  return {
    STRICKNANI_TEST_URL: url,
    STRICKNANI_TEST_EMAIL: email,
    STRICKNANI_TEST_PASSWORD: password,
  };
}

/** Run a fixture command while suppressing its potentially sensitive output. */
export function runCmd(
  argv: string[],
  opts: {
    env: Record<string, string>;
    inputBytes?: Buffer;
    timeoutSeconds?: number;
  },
): void {
  const [command, ...args] = argv;
  if (command === undefined) {
    throw new FixtureError("fixture command could not be completed");
  }
  let result: ReturnType<typeof spawnSync>;
  try {
    result = fixtureDeps.spawnSync(command, args, {
      env: { ...opts.env },
      input: opts.inputBytes,
      stdio: ["pipe", "ignore", "ignore"],
      timeout: (opts.timeoutSeconds ?? 30) * 1000,
    });
  } catch {
    throw new FixtureError("fixture command could not be completed");
  }
  if (result.error) {
    throw new FixtureError("fixture command could not be completed");
  }
  if (result.status !== 0) throw new FixtureError("fixture command failed");
}

/** Register a value with the GitHub Actions log masker when running there. */
export function maskGithubSecret(value: string): void {
  if ((process.env.GITHUB_ACTIONS ?? "").toLowerCase() === "true") {
    process.stdout.write(`::add-mask::${value}\n`);
  }
}

export function processStartTime(pid: number): string | null {
  try {
    const contents = readFileSync(`/proc/${pid}/stat`, "ascii");
    const fields = contents.slice(contents.lastIndexOf(")") + 2).split(/\s+/);
    return fields[19] ?? null;
  } catch {
    return null;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((done) => setTimeout(done, ms));
}

function killGroup(pid: number, signal: NodeJS.Signals): boolean {
  try {
    process.kill(-pid, signal);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ESRCH") return false;
    throw error;
  }
}

export async function safeTerminate(
  pid: number,
  expectedStart: string,
): Promise<void> {
  if (processStartTime(pid) !== expectedStart) return;
  if (!killGroup(pid, "SIGTERM")) return;
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (processStartTime(pid) !== expectedStart) return;
    await sleep(100);
  }
  if (processStartTime(pid) === expectedStart) killGroup(pid, "SIGKILL");
}

function validateBrowserAuthority(
  value: string | null | undefined,
): string | null {
  if (value === undefined || value === null) return null;
  const match =
    typeof value === "string" ? BROWSER_AUTHORITY.exec(value) : null;
  if (match === null || Number(match[1]) > 65535) {
    throw new FixtureError(
      "browser authority must be a synthetic device hostname",
    );
  }
  return value;
}

export function serverEnvironment(
  runtime: string,
  port: number,
  webRoot?: string,
  signupEnabled = false,
  allowedBrowserAuthority?: string,
): Record<string, string> {
  const browserAuthority = validateBrowserAuthority(allowedBrowserAuthority);
  const environment: Record<string, string> = {
    PATH: process.env.PATH ?? "/usr/bin:/bin",
    HOME: runtime,
    TMPDIR: runtime,
    STRICKNANI_DATABASE_URL: `sqlite:${join(runtime, "fixture.sqlite3")}`,
    STRICKNANI_MEDIA_ROOT: join(runtime, "media"),
    BIND_HOST: "127.0.0.1",
    BIND_PORT: String(port),
    AI_ENABLED: "false",
    OCR_ENABLED: "false",
    EXTERNAL_IMPORTS_ENABLED: "false",
    WAYBACK_ENABLED: "false",
    // The fixture must not call gravatar.com; with no network the fallback
    // route 404s and the browser logs it as a console error.
    GRAVATAR_ENABLED: "false",
  };
  if (browserAuthority !== null) {
    environment.TRUSTED_HOSTS = [
      "localhost",
      "127.0.0.1",
      "[::1]",
      browserAuthority,
    ].join(",");
  }
  if (webRoot !== undefined) {
    const resolvedWebRoot = resolveNonStrict(webRoot);
    if (!isDirectory(resolvedWebRoot)) {
      throw new FixtureError("web root must be an existing directory");
    }
    environment.WEB_ROOT = resolvedWebRoot;
  }
  if (signupEnabled) environment.SIGNUP_ENABLED = "true";
  return environment;
}

function isDirectory(path: string): boolean {
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

function isExecutableFile(path: string): boolean {
  try {
    if (!statSync(path).isFile()) return false;
    accessSync(path, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

export function unusedPort(): Promise<number> {
  return new Promise((done, fail) => {
    const listener = createServer();
    listener.once("error", fail);
    listener.listen(0, "127.0.0.1", () => {
      const address = listener.address();
      const port = typeof address === "object" && address ? address.port : 0;
      listener.close(() => (port ? done(port) : fail(new Error("no port"))));
    });
  });
}

export async function waitForHealth(
  port: number,
  process_: FixtureProcess,
): Promise<void> {
  const url = `http://127.0.0.1:${port}/healthz`;
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    if (process_.exited()) {
      throw new FixtureError(
        "Rust fixture server exited before becoming healthy",
      );
    }
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
      await response.arrayBuffer();
      if (response.status === 200) return;
    } catch {
      // Not listening yet.
    }
    await sleep(250);
  }
  throw new FixtureError("Rust fixture server did not become healthy");
}

export interface StartFixtureOptions {
  binary: string;
  tempDir: string;
  fixtureFile: string;
  stateFile: string;
  webRoot?: string;
  serverBinary?: string;
  ocrMode?: "success" | "failure";
  archiveMode?: "success" | "failure";
  signupEnabled?: boolean;
  allowedBrowserAuthority?: string;
}

/** Start one local server and write private credentials for the runner. */
export async function startFixture(o: StartFixtureOptions): Promise<void> {
  const ocrMode = o.ocrMode ?? "success";
  const archiveMode = o.archiveMode ?? "success";
  const signupEnabled = o.signupEnabled ?? false;
  const browserAuthority = validateBrowserAuthority(o.allowedBrowserAuthority);
  const binaryPath = resolveNonStrict(o.binary);
  if (!isExecutableFile(binaryPath)) {
    throw new FixtureError("Rust server binary is not executable");
  }
  let serverPath = binaryPath;
  if (o.serverBinary !== undefined) {
    serverPath = resolveNonStrict(o.serverBinary);
    if (!isExecutableFile(serverPath)) {
      throw new FixtureError("Rust fixture server binary is not executable");
    }
  }
  if (ocrMode !== "success" && ocrMode !== "failure") {
    throw new FixtureError("OCR fixture mode must be success or failure");
  }
  if (archiveMode !== "success" && archiveMode !== "failure") {
    throw new FixtureError("archive fixture mode must be success or failure");
  }
  if (typeof signupEnabled !== "boolean") {
    throw new FixtureError("signup_enabled must be a boolean");
  }
  if (
    o.serverBinary === undefined &&
    (ocrMode !== "success" || archiveMode !== "success")
  ) {
    throw new FixtureError(
      "fixture service modes require a fixture server binary",
    );
  }
  let webRoot: string | undefined;
  if (o.webRoot !== undefined) {
    webRoot = resolveNonStrict(o.webRoot);
    if (!isDirectory(webRoot)) {
      throw new FixtureError("web root must be an existing directory");
    }
  }
  const fixtureTarget = outsideRepository(o.fixtureFile, "fixture file");
  const stateTarget = outsideRepository(o.stateFile, "state file");
  const base = outsideRepository(o.tempDir, "runtime directory");
  mkdirSync(base, { mode: 0o700, recursive: true });
  const runtime = join(
    base,
    `stricknani-fixture-${randomBytes(12).toString("hex")}`,
  );
  mkdirSync(runtime, { mode: 0o700 });
  const owner = randomBytes(24).toString("hex");
  privateWrite(join(runtime, OWNER_FILE), Buffer.from(owner, "ascii"));
  mkdirSync(join(runtime, "media"), { mode: 0o700 });
  const port = await fixtureDeps.unusedPort();
  const started: { child: FixtureProcess | null } = { child: null };
  let fixtureCreated = false;
  let stateCreated = false;
  try {
    const env = serverEnvironment(
      runtime,
      port,
      webRoot,
      signupEnabled,
      browserAuthority ?? undefined,
    );
    const email = `rust-replay-${randomBytes(8).toString("hex")}@example.invalid`;
    const password = randomBytes(32).toString("base64url");
    maskGithubSecret(email);
    maskGithubSecret(password);
    fixtureDeps.runCmd([binaryPath, "create-user", email], {
      env,
      inputBytes: Buffer.from(`${password}\n`),
    });
    const logFd = openSync(
      join(runtime, "server.log"),
      constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY,
      0o600,
    );
    let oldUmask: number | null = null;
    try {
      try {
        oldUmask = process.umask(0o077);
      } catch {
        oldUmask = null; // not permitted in worker threads
      }
      started.child = fixtureDeps.spawnServer(
        o.serverBinary === undefined
          ? [serverPath]
          : [
              serverPath,
              "--document-worker",
              binaryPath,
              "--fixture-runtime",
              runtime,
              "--ocr-mode",
              ocrMode,
              "--archive-mode",
              archiveMode,
            ],
        env,
        logFd,
      );
    } finally {
      if (oldUmask !== null) process.umask(oldUmask);
      closeSync(logFd);
    }
    const child = started.child;
    if (child === null)
      throw new FixtureError("could not track Rust fixture process");
    const pid = child.pid;
    const startTime =
      pid === undefined ? null : fixtureDeps.processStartTime(pid);
    if (pid === undefined || startTime === null) {
      throw new FixtureError("could not track Rust fixture process");
    }
    writePrivateJson(stateTarget, {
      pid,
      process_start: startTime,
      runtime,
      fixture: fixtureTarget,
      owner,
    });
    stateCreated = true;
    await fixtureDeps.waitForHealth(port, child);
    writePrivateJson(fixtureTarget, {
      url: `http://127.0.0.1:${port}`,
      email,
      password,
    });
    fixtureCreated = true;
  } catch (error) {
    const childPid = started.child?.pid;
    if (childPid !== undefined) {
      const startTime = fixtureDeps.processStartTime(childPid);
      if (startTime !== null) {
        await fixtureDeps.safeTerminate(childPid, startTime);
      }
    }
    if (fixtureCreated) rmSync(fixtureTarget, { force: true });
    if (stateCreated) rmSync(stateTarget, { force: true });
    rmSync(runtime, { recursive: true, force: true });
    throw error;
  }
}

/** Stop and remove only the fixture whose private owner marker matches state. */
export async function stopFixture(stateFile: string): Promise<void> {
  const stateTarget = outsideRepository(stateFile, "state file");
  let pid: number;
  let processStart: string;
  let runtimeValue: string;
  let fixtureValue: string;
  let owner: string;
  try {
    const state = JSON.parse(readFileSync(stateTarget, "utf-8")) as Record<
      string,
      unknown
    >;
    pid = Number(state.pid);
    if (!Number.isInteger(pid)) throw new TypeError("pid");
    processStart = String(state.process_start);
    if (typeof state.runtime !== "string") throw new TypeError("runtime");
    if (typeof state.fixture !== "string") throw new TypeError("fixture");
    runtimeValue = state.runtime;
    fixtureValue = state.fixture;
    owner = String(state.owner);
  } catch {
    throw new FixtureError("fixture state is missing or invalid");
  }
  const runtime = outsideRepository(runtimeValue, "runtime directory");
  const fixture = outsideRepository(fixtureValue, "fixture file");
  if (!isDirectory(runtime)) {
    throw new FixtureError("fixture runtime directory is missing");
  }
  let marker: string;
  try {
    marker = readFileSync(join(runtime, OWNER_FILE), "ascii");
  } catch {
    throw new FixtureError("fixture owner marker is missing");
  }
  if (marker !== owner) {
    throw new FixtureError("fixture owner marker does not match");
  }
  await fixtureDeps.safeTerminate(pid, processStart);
  rmSync(fixture, { force: true });
  rmSync(runtime, { recursive: true });
  rmSync(stateTarget, { force: true });
}
