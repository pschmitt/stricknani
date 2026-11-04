/**
 * Run configuration and fixture lifecycle shared by every e2e spec.
 *
 * Environment:
 *   STRICKNANI_SERVER_BINARY     Rust server binary (required)
 *   STRICKNANI_WEB_ROOT          packaged web root served as WEB_ROOT (required)
 *   STRICKNANI_FIXTURE_BINARY    optional fixture companion server binary
 *   STRICKNANI_BROWSER           chromium (default), firefox or webkit
 *   STRICKNANI_BROWSER_EXECUTABLE optional browser executable path
 *   STRICKNANI_E2E_DIR           parent of the private run directories
 *   STRICKNANI_E2E_KEEP=1        keep the private run directory afterwards
 */
import { randomBytes } from "node:crypto";
import {
  accessSync,
  chmodSync,
  constants,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { chromium, firefox, webkit, type Browser } from "@playwright/test";
import {
  AcceptanceFailure,
  loopbackOrigin,
  privateScreenshotDir,
  privateWorkDir,
} from "./common";
import {
  FixtureError,
  loadFixtureEnvironment,
  startFixture,
  stopFixture,
  type FixtureEnvironment,
  type StartFixtureOptions,
} from "./fixture";
import { seedFixture } from "./seed";

export interface E2eConfig {
  binary: string;
  webRoot: string;
  serviceFixtureBinary?: string;
  browser: "chromium" | "firefox" | "webkit";
  browserExecutable?: string;
}

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === "") {
    throw new FixtureError(`${name} is required`);
  }
  return value;
}

function optionalEnv(name: string): string | undefined {
  const value = process.env[name];
  return value === undefined || value === "" ? undefined : value;
}

export function e2eConfig(): E2eConfig {
  const browser = optionalEnv("STRICKNANI_BROWSER") ?? "chromium";
  if (browser !== "chromium" && browser !== "firefox" && browser !== "webkit") {
    throw new FixtureError(
      "STRICKNANI_BROWSER must be chromium, firefox, or webkit",
    );
  }
  return {
    binary: requiredEnv("STRICKNANI_SERVER_BINARY"),
    webRoot: requiredEnv("STRICKNANI_WEB_ROOT"),
    serviceFixtureBinary: optionalEnv("STRICKNANI_FIXTURE_BINARY"),
    browser,
    browserExecutable: optionalEnv("STRICKNANI_BROWSER_EXECUTABLE"),
  };
}

export interface LiveFixture {
  privateDir: string;
  fixtureFile: string;
  stateFile: string;
  screenshotDir: string;
  env: FixtureEnvironment;
  /** `loopbackOrigin(env.STRICKNANI_RUST_TEST_URL)` */
  origin: string;
}

export type LiveFixtureOptions = Partial<
  Omit<StartFixtureOptions, "binary" | "tempDir" | "fixtureFile" | "stateFile">
> & {
  /** Seed the demo records (default true). */
  seed?: boolean;
  screenshotDetail?: boolean;
};

/**
 * Start a fresh fixture in a new private directory, run `body`, then always
 * stop the fixture and remove the directory (unless STRICKNANI_E2E_KEEP=1).
 */
export async function withLiveFixture<T>(
  name: string,
  opts: LiveFixtureOptions,
  body: (fixture: LiveFixture) => Promise<T>,
): Promise<T> {
  const cfg = e2eConfig();
  const base = process.env.STRICKNANI_E2E_DIR || tmpdir();
  const suffix = randomBytes(6).toString("hex");
  // Fresh, nonexistent leaf: the fixture owns and later removes this directory.
  const privateDir = privateWorkDir(join(resolve(base), `${name}-${suffix}`));
  const fixtureFile = join(privateDir, "fixture.json");
  const stateFile = join(privateDir, "fixture-state.json");
  const tokenFile = join(privateDir, "seed-token.json");
  let stopFailed = false;
  try {
    const { seed = true, screenshotDetail, ...startOptions } = opts;
    await startFixture({
      webRoot: cfg.webRoot,
      ...startOptions,
      binary: cfg.binary,
      tempDir: join(privateDir, "runtime"),
      fixtureFile,
      stateFile,
    });
    try {
      if (seed) {
        try {
          await seedFixture(fixtureFile, tokenFile, { screenshotDetail });
        } finally {
          rmSync(tokenFile, { force: true });
        }
      }
      const env = loadFixtureEnvironment(fixtureFile);
      const screenshotDir = privateScreenshotDir(
        join(privateDir, "screenshots"),
      );
      return await body({
        privateDir,
        fixtureFile,
        stateFile,
        screenshotDir,
        env,
        origin: loopbackOrigin(env.STRICKNANI_RUST_TEST_URL),
      });
    } finally {
      try {
        await stopFixture(stateFile);
      } catch {
        stopFailed = true;
      }
    }
  } finally {
    // Never remove a runtime that may still be owned by a live server.
    if (process.env.STRICKNANI_E2E_KEEP !== "1" && !stopFailed) {
      rmSync(privateDir, { recursive: true, force: true });
    }
  }
}

/** Launch the configured browser headless or fail with a sanitised message. */
export async function launchBrowser(cfg: E2eConfig): Promise<Browser> {
  let executablePath: string | undefined;
  if (cfg.browserExecutable !== undefined) {
    executablePath = resolve(cfg.browserExecutable);
    let usable = false;
    try {
      usable = statSync(executablePath).isFile();
      if (usable) accessSync(executablePath, constants.X_OK);
    } catch {
      usable = false;
    }
    if (!usable) {
      throw new AcceptanceFailure(
        "configured browser executable is unavailable",
      );
    }
  }
  const types = { chromium, firefox, webkit };
  try {
    return await types[cfg.browser].launch({ headless: true, executablePath });
  } catch {
    throw new AcceptanceFailure(
      `configured ${cfg.browser} could not start in headless mode`,
    );
  }
}

/** Failure-location marker (browser-stage.txt next to the screenshot dir). */
export function stageMarker(screenshotsParent: string): {
  mark(value: string): void;
  current(): string;
} {
  const stageFile = join(screenshotsParent, "browser-stage.txt");
  let currentStage = "";
  return {
    mark(value: string): void {
      currentStage = value;
      writeFileSync(stageFile, value, { encoding: "ascii" });
      chmodSync(stageFile, 0o600);
    },
    current(): string {
      return currentStage;
    },
  };
}
