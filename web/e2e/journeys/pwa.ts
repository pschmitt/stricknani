import { closeBrowser } from "../lib/common";
// App-scoped offline worker journey against a disposable Rust server.
// Port of ci/stricknani/web_pwa_browser.py (Chromium only: it uses CDP).
import {
  accessSync,
  constants,
  cpSync,
  existsSync,
  readFileSync,
  rmSync,
  statSync,
} from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";

import {
  chromium,
  type Browser,
  type BrowserContext,
  type CDPSession,
  type Dialog,
  type Page,
  type Request,
  type Response,
  type Route,
} from "@playwright/test";

import {
  AcceptanceFailure,
  check,
  playwrightErrorCategory,
} from "../lib/common";
import {
  FixtureError,
  fixtureDeps,
  loadFixtureEnvironment,
  startFixture,
  stopFixture,
} from "../lib/fixture";
import { stageMarker, type E2eConfig, type LiveFixture } from "../lib/harness";
import {
  APP_CACHE_PREFIX,
  IMMUTABLE_ASSET_PREFIX,
  REQUIRED_OFFLINE_ASSETS,
  buildCandidateWebRoot,
  countOccurrences,
  isRecord,
  isSubset,
  readAssetManifest,
  removePrefix,
  setsEqual,
  sha256Hex,
  waitForAsyncCondition,
  writeNextWebRoot,
} from "../lib/pwa";

const REQUIRED_PATHS = [...REQUIRED_OFFLINE_ASSETS];

type PwaWindow = Window & { __pwaInstallStates?: string[] };

interface VersionSnapshot {
  scope?: string;
  active?: string;
  activeAppVersion?: string;
  activeWorkerVersion?: string;
  activeState?: string;
  installing?: string;
  installingState?: string;
  waiting?: string;
  waitingAppVersion?: string;
  waitingWorkerVersion?: string;
  waitingState?: string;
}

function pathOf(url: string): string | null {
  try {
    return new URL(url).pathname;
  } catch {
    return null;
  }
}

/** Origin and port of the fixture URL (python `_origin`). */
function parseOrigin(url: string): { origin: string; port: number } {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new AcceptanceFailure("fixture URL is invalid");
  }
  const port = Number(parsed.port);
  const loopback =
    parsed.hostname === "localhost" ||
    parsed.hostname === "127.0.0.1" ||
    parsed.hostname === "[::1]";
  if (
    parsed.protocol !== "http:" ||
    !loopback ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.search !== "" ||
    parsed.hash !== "" ||
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535
  ) {
    throw new AcceptanceFailure("fixture URL must be plain HTTP on loopback");
  }
  return { origin: `http://127.0.0.1:${port}`, port };
}

async function versionSnapshot(page: Page): Promise<VersionSnapshot> {
  const value: unknown = await page.evaluate(async () => {
    const registration = await navigator.serviceWorker.getRegistration("/app/");
    if (!registration) return { scope: "", active: "", waiting: "" };
    type Reply = {
      version?: string;
      appVersion?: string;
      workerVersion?: string;
    } | null;
    const ask = (worker: ServiceWorker | null | undefined) =>
      new Promise<Reply>((resolveReply) => {
        if (!worker) {
          resolveReply(null);
          return;
        }
        const channel = new MessageChannel();
        const timer = setTimeout(() => resolveReply(null), 1500);
        channel.port1.onmessage = (event: MessageEvent) => {
          clearTimeout(timer);
          resolveReply((event.data ?? null) as Reply);
          channel.port1.close();
        };
        worker.postMessage({ type: "GET_VERSION" }, [channel.port2]);
      });
    const active = await ask(registration.active);
    const installing = await ask(registration.installing);
    const waiting = await ask(registration.waiting);
    return {
      scope: new URL(registration.scope).pathname,
      active: active?.version ?? "",
      activeAppVersion: active?.appVersion ?? "",
      activeWorkerVersion: active?.workerVersion ?? "",
      activeState: registration.active?.state ?? "",
      installing: installing?.version ?? "",
      installingState: registration.installing?.state ?? "",
      waiting: waiting?.version ?? "",
      waitingAppVersion: waiting?.appVersion ?? "",
      waitingWorkerVersion: waiting?.workerVersion ?? "",
      waitingState: registration.waiting?.state ?? "",
    };
  });
  if (!isRecord(value)) {
    throw new AcceptanceFailure(
      "service worker registration state is malformed",
    );
  }
  return value as VersionSnapshot;
}

async function cacheSnapshot(page: Page): Promise<Record<string, string[]>> {
  const value: unknown = await page.evaluate(async (prefix: string) => {
    const result: Record<string, string[]> = {};
    const names = (await caches.keys()).filter((key) => key.startsWith(prefix));
    for (const name of names) {
      const cache = await caches.open(name);
      result[name] = (await cache.keys())
        .map((request) => new URL(request.url).pathname)
        .sort();
    }
    return result;
  }, APP_CACHE_PREFIX);
  if (
    !isRecord(value) ||
    Object.values(value).some(
      (paths) =>
        !Array.isArray(paths) || paths.some((path) => typeof path !== "string"),
    )
  ) {
    throw new AcceptanceFailure("CacheStorage state is malformed");
  }
  return value as Record<string, string[]>;
}

async function loadedImmutableAssets(page: Page): Promise<Set<string>> {
  const value: unknown = await page.evaluate(() => {
    return performance
      .getEntriesByType("resource")
      .map((entry) => {
        try {
          return new URL(entry.name).pathname;
        } catch {
          return "";
        }
      })
      .filter((path) => path.startsWith("/app/_app/immutable/"));
  });
  if (!Array.isArray(value) || value.some((path) => typeof path !== "string")) {
    throw new AcceptanceFailure("browser resource list is malformed");
  }
  return new Set(
    (value as string[]).filter(
      (path) => path.endsWith(".js") || path.endsWith(".css"),
    ),
  );
}

/** Poll an awaited in-page condition; `poll` evaluates it once. */
function waitAsync(
  page: Page,
  poll: () => Promise<unknown>,
  description: string,
  timeoutMs?: number,
): Promise<void> {
  return waitForAsyncCondition(page, poll, description, timeoutMs);
}

async function waitForActiveWorker(
  page: Page,
  expectedVersion: string,
): Promise<void> {
  await waitAsync(
    page,
    () =>
      page.evaluate(async (expected: string) => {
        const registration =
          await navigator.serviceWorker.getRegistration("/app/");
        const worker = registration?.active;
        if (!registration || worker?.state !== "activated") return false;
        const version = await new Promise<string>((resolveVersion) => {
          const channel = new MessageChannel();
          const timer = setTimeout(() => resolveVersion(""), 1000);
          channel.port1.onmessage = (event: MessageEvent) => {
            clearTimeout(timer);
            resolveVersion(event.data?.version ?? "");
            channel.port1.close();
          };
          worker.postMessage({ type: "GET_VERSION" }, [channel.port2]);
        });
        return (
          version === expected &&
          worker.state === "activated" &&
          registration.active === worker
        );
      }, expectedVersion),
    "active app worker version",
  );
}

async function waitForControlledWorker(
  page: Page,
  expectedVersion: string,
): Promise<void> {
  try {
    await page.waitForFunction(
      () =>
        Boolean(
          navigator.serviceWorker.controller?.scriptURL.endsWith("/app/sw.js"),
        ),
      undefined,
      { timeout: 10_000 },
    );
  } catch {
    const state = await page.evaluate(async () => {
      const registration =
        await navigator.serviceWorker.getRegistration("/app/");
      return {
        page: location.pathname,
        controller: navigator.serviceWorker.controller
          ? new URL(navigator.serviceWorker.controller.scriptURL).pathname
          : "",
        scope: registration ? new URL(registration.scope).pathname : "",
        active: registration?.active?.state ?? "",
      };
    });
    throw new AcceptanceFailure(
      `app worker did not control page (safe paths/state: ${JSON.stringify(state)})`,
    );
  }
  try {
    await waitAsync(
      page,
      () =>
        page.evaluate((expected: string) => {
          const worker = navigator.serviceWorker.controller;
          if (!worker) return false;
          return new Promise<boolean>((resolveMatch) => {
            const channel = new MessageChannel();
            const timer = setTimeout(() => resolveMatch(false), 1000);
            channel.port1.onmessage = (event: MessageEvent) => {
              clearTimeout(timer);
              resolveMatch(event.data?.version === expected);
              channel.port1.close();
            };
            worker.postMessage({ type: "GET_VERSION" }, [channel.port2]);
          }).then(
            (versionMatches) =>
              versionMatches && navigator.serviceWorker.controller === worker,
          );
        }, expectedVersion),
      "app controller version",
      10_000,
    );
  } catch {
    const state = await page.evaluate(async () => {
      const controller = navigator.serviceWorker.controller;
      const scope = await Promise.race([
        navigator.serviceWorker.ready.then(
          (value) => new URL(value.scope).pathname,
        ),
        new Promise<string>((resolveScope) =>
          setTimeout(() => resolveScope(""), 1000),
        ),
      ]);
      return {
        page: location.pathname,
        controller: controller ? new URL(controller.scriptURL).pathname : "",
        controllerState: controller?.state ?? "",
        scope,
      };
    });
    throw new AcceptanceFailure(
      "app controller did not confirm the expected version " +
        `(safe state: ${JSON.stringify(state)})`,
    );
  }
}

async function waitForInitialWorker(
  page: Page,
  expectedVersion: string,
): Promise<void> {
  await waitForActiveWorker(page, expectedVersion);
  await page.reload({ waitUntil: "networkidle" });
  await waitForControlledWorker(page, expectedVersion);
}

interface PrepareUpdate {
  binary: string;
  webRoot: string;
  privateDir: string;
  fixtureFile: string;
  stateFile: string;
  origin: string;
  port: number;
  secondRoot: string;
  versionMarker?: string;
  missingAsset?: string;
  candidateBuildRoot?: string;
  runtimeName?: string;
}

/**
 * Stage a new web root and restart the fixture on the SAME origin with it.
 * Returns the worker version the new root serves.
 */
async function prepareBrowserUpdate(p: PrepareUpdate): Promise<string> {
  let expectedVersion: string;
  if (p.candidateBuildRoot === undefined) {
    expectedVersion = writeNextWebRoot(
      p.webRoot,
      p.secondRoot,
      p.versionMarker ?? "disposable browser update fixture",
    );
  } else {
    // Same copy semantics as shutil.copytree(..., symlinks=True).
    cpSync(p.candidateBuildRoot, p.secondRoot, {
      recursive: true,
      verbatimSymlinks: true,
    });
    let manifest: unknown;
    try {
      manifest = JSON.parse(
        readFileSync(join(p.secondRoot, "pwa-assets.json"), "utf8"),
      ) as unknown;
    } catch {
      throw new AcceptanceFailure("candidate PWA worker version is invalid");
    }
    const version = isRecord(manifest) ? manifest.version : undefined;
    if (typeof version !== "string") {
      throw new AcceptanceFailure("candidate PWA worker version is invalid");
    }
    expectedVersion = version;
  }
  if (p.missingAsset !== undefined) {
    if (!p.missingAsset.startsWith("/app/") || p.missingAsset.includes("..")) {
      throw new AcceptanceFailure(
        "failed-install fixture asset path is invalid",
      );
    }
    try {
      rmSync(join(p.secondRoot, removePrefix(p.missingAsset, "/app/")));
    } catch {
      throw new AcceptanceFailure(
        "failed-install fixture asset is unavailable",
      );
    }
  }
  await stopFixture(p.stateFile);
  // Pin the replacement server to the browser's origin (the python runner
  // monkeypatched rust_fixture._unused_port the same way).
  const originalPortAllocator = fixtureDeps.unusedPort;
  fixtureDeps.unusedPort = () => Promise.resolve(p.port);
  try {
    await startFixture({
      binary: p.binary,
      tempDir: join(p.privateDir, p.runtimeName ?? "runtime-v2"),
      fixtureFile: p.fixtureFile,
      stateFile: p.stateFile,
      webRoot: p.secondRoot,
    });
  } finally {
    fixtureDeps.unusedPort = originalPortAllocator;
  }
  // Confirm that the replacement server is still on the original origin.
  const fixture = loadFixtureEnvironment(p.fixtureFile);
  const replacement = parseOrigin(fixture.STRICKNANI_TEST_URL);
  check(
    replacement.origin === p.origin && replacement.port === p.port,
    "replacement fixture did not retain the browser origin",
  );
  return expectedVersion;
}

/**
 * Sign the browser context in again after a fixture restart. Every fixture
 * runtime has its own database, so a restart ends the session. Signing in
 * through the API sets the cookie for every tab without navigating one, so
 * open tabs keep the app version they loaded.
 */
async function signInThroughApi(
  page: Page,
  fixtureFile: string,
): Promise<void> {
  const current = loadFixtureEnvironment(fixtureFile);
  const status: unknown = await page.evaluate(
    async ({ email, password }: { email: string; password: string }) => {
      const session = await (
        await fetch("/api/v2/session", { cache: "no-store" })
      ).json();
      const response = await fetch("/api/v2/auth/login", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-csrf-token": session?.data?.csrf_token ?? "",
        },
        body: JSON.stringify({ email, password }),
      });
      return response.status;
    },
    {
      email: current.STRICKNANI_TEST_EMAIL,
      password: current.STRICKNANI_TEST_PASSWORD,
    },
  );
  check(
    status === 200,
    `API sign-in after a fixture restart failed (${String(status)})`,
  );
}

function isCspMessage(text: string): boolean {
  const lowered = text.toLowerCase();
  return (
    lowered.includes("content security policy") ||
    lowered.includes("refused to")
  );
}

function resolveChromium(executable: string | undefined): string | undefined {
  if (executable === undefined) return undefined;
  let expanded = executable;
  if (expanded === "~") expanded = homedir();
  else if (expanded.startsWith("~/"))
    expanded = join(homedir(), expanded.slice(2));
  const resolved = resolve(expanded);
  try {
    accessSync(resolved, constants.X_OK);
    if (!statSync(resolved).isFile()) throw new Error("not a file");
  } catch {
    throw new AcceptanceFailure(
      "configured Chromium executable is unavailable",
    );
  }
  return resolved;
}

const MOBILE_VIEWPORT = { width: 390, height: 844 };

export async function runPwaJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const stage = stageMarker(f.privateDir);
  try {
    await runAcceptance(f, cfg, stage);
  } catch (error) {
    if (error instanceof AcceptanceFailure || error instanceof FixtureError) {
      throw error;
    }
    throw new AcceptanceFailure(
      `PWA browser acceptance failed during ${stage.current() || "unknown stage"} ` +
        `(${playwrightErrorCategory(error)}); details suppressed.`,
    );
  }
}

async function runAcceptance(
  f: LiveFixture,
  cfg: E2eConfig,
  stage: ReturnType<typeof stageMarker>,
): Promise<void> {
  const markStage = (value: string) => stage.mark(value);
  const privateDir = f.privateDir;
  const fixtureFile = f.fixtureFile;
  const stateFile = f.stateFile;
  const binary = cfg.binary;
  const webRoot = cfg.webRoot;
  const fixture = loadFixtureEnvironment(fixtureFile);
  const { origin, port } = parseOrigin(fixture.STRICKNANI_TEST_URL);
  const email = fixture.STRICKNANI_TEST_EMAIL;
  const password = fixture.STRICKNANI_TEST_PASSWORD;
  const {
    version: expectedVersion,
    appCodeVersion: expectedAppVersion,
    assets: expectedAssetList,
  } = readAssetManifest(webRoot);
  const expectedAssets = new Set(expectedAssetList);
  check(
    isSubset(REQUIRED_OFFLINE_ASSETS, expectedAssets),
    "generated allowlist omits a required offline fallback asset",
  );
  const secondRoot = join(privateDir, "web-root-next");
  const screenshots = f.screenshotDir;
  const chromiumExecutable = resolveChromium(cfg.browserExecutable);

  const pageErrors: boolean[] = [];
  const cspErrors: boolean[] = [];
  const activationWorkerErrors: Array<[string, number | null]> = [];
  const serviceWorkerVersions: Array<{ versionId: string; status: string }> =
    [];
  const serviceWorkerAssetRequests: string[] = [];
  const serviceWorkerAssetResponses: string[] = [];
  const serviceWorkerAssetFinished: string[] = [];
  const serviceWorkerAssetFailures: string[] = [];
  const reopenEvents: string[] = [];

  const recordWorkerError = (event: unknown): void => {
    const current = stage.current();
    if (
      !current.startsWith("update") &&
      !current.startsWith("committing") &&
      !current.startsWith("reloading")
    ) {
      return;
    }
    if (!isRecord(event)) {
      activationWorkerErrors.push(["unknown", null]);
      return;
    }
    // The CDP event nests the details under `errorMessage`.
    const details = isRecord(event.errorMessage) ? event.errorMessage : event;
    const message = details.errorMessage;
    let errorClass = "other";
    if (typeof message === "string") {
      const match = /\b([A-Za-z]+Error)\b/.exec(message);
      if (match?.[1] !== undefined) errorClass = match[1];
    }
    const line = details.lineNumber;
    activationWorkerErrors.push([
      errorClass,
      typeof line === "number" && Number.isInteger(line) ? line : null,
    ]);
  };

  const recordWorkerVersions = (event: unknown): void => {
    if (!isRecord(event) || !Array.isArray(event.versions)) return;
    for (const version of event.versions as unknown[]) {
      if (!isRecord(version)) continue;
      const scriptUrl = version.scriptURL;
      const versionId = version.versionId;
      const status = version.status;
      if (
        typeof scriptUrl === "string" &&
        pathOf(scriptUrl) === "/app/sw.js" &&
        typeof versionId === "string" &&
        typeof status === "string"
      ) {
        serviceWorkerVersions.push({ versionId, status });
      }
    }
  };

  const workerAssetPath = (request: Request): string | null => {
    const path = pathOf(request.url());
    if (path !== null && expectedAssets.has(path) && request.serviceWorker()) {
      return path;
    }
    return null;
  };

  /** Count page exceptions and CSP console messages of one page. */
  const watchPage = (target: Page): void => {
    target.on("pageerror", () => {
      pageErrors.push(true);
    });
    target.on("console", (message) => {
      if (isCspMessage(message.text())) cspErrors.push(true);
    });
  };

  markStage("launching packaged Chromium");
  let browser: Browser;
  try {
    browser = await chromium.launch({
      headless: true,
      executablePath: chromiumExecutable,
    });
  } catch {
    throw new AcceptanceFailure("configured Chromium could not start");
  }

  let boundaryTab: Page | null = null;
  let boundaryCdp: CDPSession | null = null;
  try {
    const context: BrowserContext = await browser.newContext({
      viewport: MOBILE_VIEWPORT,
      serviceWorkers: "allow",
      reducedMotion: "reduce",
      colorScheme: "light",
      locale: "en-US",
    });
    context.on("request", (request: Request) => {
      const path = workerAssetPath(request);
      if (path !== null) serviceWorkerAssetRequests.push(path);
    });
    context.on("response", (response: Response) => {
      const path = workerAssetPath(response.request());
      if (path !== null) {
        serviceWorkerAssetResponses.push(`${path}:${response.status()}`);
      }
    });
    context.on("requestfinished", (request: Request) => {
      const path = workerAssetPath(request);
      if (path !== null) serviceWorkerAssetFinished.push(path);
    });
    context.on("requestfailed", (request: Request) => {
      const path = workerAssetPath(request);
      if (path !== null) serviceWorkerAssetFailures.push(path);
    });
    const page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send("ServiceWorker.enable");
    cdp.on("ServiceWorker.workerErrorReported", recordWorkerError);
    cdp.on("ServiceWorker.workerVersionUpdated", recordWorkerVersions);
    watchPage(page);
    const monitor = await context.newPage();
    monitor.on("request", (request) => {
      const path = pathOf(request.url());
      if (path?.startsWith("/app/")) reopenEvents.push(`request:${path}`);
    });
    monitor.on("response", (response) => {
      const path = pathOf(response.url());
      if (path?.startsWith("/app/")) {
        reopenEvents.push(`response:${response.status()}:${path}`);
      }
    });
    monitor.on("requestfailed", (request) => {
      const path = pathOf(request.url());
      if (path?.startsWith("/app/")) reopenEvents.push(`failed:${path}`);
    });
    watchPage(monitor);
    const health = await monitor.goto(`${origin}/healthz`, {
      waitUntil: "domcontentloaded",
    });
    check(
      health !== null && health.status() === 200,
      "Rust fixture health endpoint failed during worker activation",
    );
    let builtWorker: Buffer;
    try {
      builtWorker = readFileSync(join(webRoot, "app-sw.js"));
    } catch {
      throw new AcceptanceFailure("generated app worker is unavailable");
    }
    const servedWorker = await monitor.request.get(`${origin}/app/sw.js`, {
      timeout: 10_000,
    });
    check(
      servedWorker.status() === 200 &&
        (await servedWorker.body()).equals(builtWorker),
      "Rust fixture did not serve the frozen generated worker bytes",
    );
    markStage("requesting the canonical app root");
    let response = await page.goto(`${origin}/app`, {
      waitUntil: "networkidle",
    });
    check(
      response !== null && response.status() === 200,
      "canonical app root failed",
    );
    markStage("checking canonical app root URL");
    check(
      page.url() === `${origin}/app/`,
      "app root without a trailing slash did not canonicalize into worker scope",
    );
    markStage("waiting for app worker registration");
    await waitAsync(
      page,
      () =>
        page.evaluate(async () => {
          const registration =
            await navigator.serviceWorker.getRegistration("/app/");
          return Boolean(registration);
        }),
      "app worker registration",
      10_000,
    );
    markStage("checking active app worker version");
    await waitForActiveWorker(page, expectedVersion);
    check(
      page.url() === `${origin}/app/`,
      "canonical app root escaped the service worker scope",
    );
    markStage("checking initial worker scope and version");
    const initialSnapshot = await versionSnapshot(page);
    check(
      initialSnapshot.scope === "/app/" &&
        initialSnapshot.active === expectedVersion,
      "initial worker is not active under the canonical app root",
    );
    markStage("checking initial offline cache");
    const initialCaches = await cacheSnapshot(page);
    check(
      Object.keys(initialCaches).length === 1,
      "initial app cache count is unexpected",
    );
    const initialPaths = new Set(Object.values(initialCaches)[0]);
    // Once the worker claims the page, lazily loaded route chunks are
    // promoted into the cache at runtime, so anything beyond the recovery
    // assets must be an immutable hashed asset.
    const unexpectedPaths = [...initialPaths]
      .filter(
        (path) =>
          !REQUIRED_OFFLINE_ASSETS.has(path) &&
          !path.startsWith(IMMUTABLE_ASSET_PREFIX),
      )
      .sort();
    check(
      isSubset(REQUIRED_PATHS, initialPaths) && unexpectedPaths.length === 0,
      "cold worker install cached something beyond the offline recovery " +
        `assets: ${JSON.stringify(unexpectedPaths)}`,
    );
    const coldInstallRequests = new Set(serviceWorkerAssetRequests);
    check(
      isSubset(REQUIRED_PATHS, coldInstallRequests),
      "browser did not observe every required offline asset fetched during install",
    );
    check(
      ![...coldInstallRequests].some((path) =>
        path.startsWith(IMMUTABLE_ASSET_PREFIX),
      ),
      "cold worker install fetched immutable route chunks before they were needed",
    );
    markStage("reloading the app root to confirm worker control");
    response = await page.reload({ waitUntil: "networkidle" });
    check(
      response !== null && response.status() === 200,
      "app root failed after the worker installed",
    );
    markStage("checking canonical app root worker control");
    await waitForControlledWorker(page, expectedVersion);
    check(
      await page.evaluate(() => {
        return Boolean(navigator.serviceWorker.controller);
      }),
      "canonical app root is not controlled by the app-scoped worker",
    );

    response = await page.goto(`${origin}/app/login`, {
      waitUntil: "networkidle",
    });
    check(
      response !== null && response.status() === 200,
      "app login route failed",
    );

    const snapshot = await versionSnapshot(page);
    check(snapshot.scope === "/app/", "worker escaped the app scope");
    check(
      snapshot.active === expectedVersion,
      "active app worker version differs from build",
    );
    check(
      snapshot.activeAppVersion === expectedAppVersion,
      "active worker compiled-app version differs from the build",
    );
    check(snapshot.waiting === "", "initial app worker unexpectedly waits");

    const workerResponse = await page.request.get(`${origin}/app/sw.js`);
    check(workerResponse.status() === 200, "app worker route failed");
    check(
      workerResponse.headers()["service-worker-allowed"] === "/app/",
      "app worker scope header is incorrect",
    );
    const workerText = await workerResponse.text();
    check(
      workerText.includes(expectedVersion) &&
        workerText.includes(
          `const APP_CODE_VERSION = "${expectedAppVersion}"`,
        ) &&
        !workerText.includes("__APP_"),
      "Rust did not serve the generated versioned worker",
    );
    // A notification click may look for an open app tab to focus; nothing
    // else in the worker (install, activation, updates) may ask other tabs.
    const notificationClick =
      /self\.addEventListener\("notificationclick",[\s\S]*?\n\}\);\n/;
    check(
      notificationClick.test(workerText),
      "app worker has no notification click handler",
    );
    const updateText = workerText.replace(notificationClick, "");
    check(
      countOccurrences(updateText, "self.skipWaiting()") === 1 &&
        countOccurrences(updateText, "self.clients.claim()") === 1 &&
        !updateText.includes("PREPARE_UPDATE") &&
        !updateText.includes("matchAll("),
      "app worker does not take over on its own or still asks other tabs",
    );

    let cacheState = await cacheSnapshot(page);
    check(
      Object.keys(cacheState).length === 1,
      "initial app cache count is unexpected",
    );
    let cachedPaths = Object.values(cacheState)[0] as string[];
    check(
      isSubset(REQUIRED_PATHS, new Set(cachedPaths)),
      "required offline recovery assets are missing from CacheStorage",
    );
    check(
      isSubset(cachedPaths, expectedAssets),
      "CacheStorage contains a path outside the exact generated allowlist",
    );
    let loadedAssets = await loadedImmutableAssets(page);
    const missingLoadedAssets = [...loadedAssets]
      .filter((path) => !cachedPaths.includes(path))
      .sort();
    check(
      missingLoadedAssets.every(
        (path) =>
          expectedAssets.has(path) &&
          path.startsWith(IMMUTABLE_ASSET_PREFIX) &&
          (path.endsWith(".js") || path.endsWith(".css")),
      ),
      "a loaded immutable asset is outside the generated public allowlist",
    );
    if (missingLoadedAssets.length > 0) {
      markStage("demand-fetching missing loaded immutable assets");
      await waitForControlledWorker(page, expectedVersion);
      for (const assetPath of missingLoadedAssets) {
        const fetchResult = await page.evaluate(async (path: string) => {
          const fetched = await window.fetch(path, {
            cache: "reload",
            credentials: "omit",
          });
          const body = await fetched.arrayBuffer();
          return {
            status: fetched.status,
            ok: fetched.ok,
            basic: fetched.type === "basic",
            redirected: fetched.redirected,
            urlMatches:
              fetched.url === new URL(path, window.location.href).href,
            bodyBytes: body.byteLength,
          };
        }, assetPath);
        check(
          fetchResult.status === 200 &&
            fetchResult.ok === true &&
            fetchResult.basic === true &&
            fetchResult.redirected === false &&
            fetchResult.urlMatches === true &&
            Number.isInteger(fetchResult.bodyBytes) &&
            fetchResult.bodyBytes > 0,
          "controlled demand fetch did not return an exact public asset",
        );
      }
      const demandArg = {
        cacheName: Object.keys(cacheState)[0] as string,
        prefix: IMMUTABLE_ASSET_PREFIX,
        paths: [...loadedAssets].sort(),
      };
      await waitAsync(
        page,
        () =>
          page.evaluate(
            async (expected: {
              cacheName: string;
              prefix: string;
              paths: string[];
            }) => {
              const cache = await caches.open(expected.cacheName);
              const cached = new Set(
                (await cache.keys()).map(
                  (request) => new URL(request.url).pathname,
                ),
              );
              const loaded = performance
                .getEntriesByType("resource")
                .flatMap((entry) => {
                  try {
                    return [new URL(entry.name).pathname];
                  } catch {
                    return [];
                  }
                })
                .filter(
                  (path) =>
                    path.startsWith(expected.prefix) &&
                    (path.endsWith(".js") || path.endsWith(".css")),
                );
              return (
                expected.paths.every((path) => cached.has(path)) &&
                loaded.every((path) => cached.has(path))
              );
            },
            demandArg,
          ),
        "loaded immutable assets to enter CacheStorage",
        10_000,
      );
      cacheState = await cacheSnapshot(page);
      check(
        Object.keys(cacheState).length === 1,
        "initial app cache count is unexpected",
      );
      cachedPaths = Object.values(cacheState)[0] as string[];
      check(
        isSubset(REQUIRED_PATHS, new Set(cachedPaths)),
        "required offline recovery assets are missing from CacheStorage",
      );
      check(
        isSubset(cachedPaths, expectedAssets),
        "CacheStorage contains a path outside the exact generated allowlist",
      );
      loadedAssets = await loadedImmutableAssets(page);
    }
    check(
      isSubset(loadedAssets, new Set(cachedPaths)),
      "an immutable asset loaded by the app was not cached on demand",
    );
    for (const forbidden of [
      "/app/messages.json",
      "/app/projects",
      "/api/v2/session",
      "/media/projects/",
    ]) {
      check(
        cachedPaths.every((path) => !path.includes(forbidden)),
        "private or mutable app content entered CacheStorage",
      );
    }
    for (const path of [
      "/app/pwa-assets.json",
      "/app/csp.json",
      "/app/service-worker.js",
    ]) {
      const hidden = await page.request.get(`${origin}${path}`);
      check(
        hidden.status() === 404,
        "internal worker build artifact is public",
      );
    }
    const catalog = await page.request.get(`${origin}/app/messages.json`);
    check(
      catalog.status() === 200 &&
        (catalog.headers()["cache-control"] ?? "").includes("no-store"),
      "translation catalog must remain uncached",
    );

    markStage("checking responsive offline fallback");
    await page.evaluate(() => {
      localStorage.setItem("stricknani.theme", "dark");
    });
    await context.setOffline(true);
    const offlineResponse = await page.goto(`${origin}/app/projects/42`, {
      waitUntil: "domcontentloaded",
      timeout: 10_000,
    });
    check(
      offlineResponse !== null && offlineResponse.status() === 200,
      "offline page did not load",
    );
    await page
      .getByRole("heading", { name: "Winky lost the thread" })
      .waitFor();
    check(
      !(await page.getByText("Winky hat den Faden verloren").isVisible()),
      "offline page shows a second language next to the user's",
    );
    check(
      await page.getByText("are not stored offline.").isVisible(),
      "offline page does not explain that private edits are not stored",
    );
    check(
      (await page.evaluate(() => {
        return document.documentElement.dataset.theme;
      })) === "dark",
      "offline page ignored the saved theme (offline-theme.js blocked?)",
    );
    for (const width of [320, 390]) {
      await page.setViewportSize({ width, height: 844 });
      const dimensions = await page.evaluate(() => {
        return {
          viewport: window.innerWidth,
          document: document.documentElement.scrollWidth,
        };
      });
      check(
        dimensions.document <= dimensions.viewport,
        "offline page overflows a phone viewport",
      );
      const retryBox = await page
        .getByRole("link", { name: "Try again" })
        .boundingBox();
      check(
        retryBox !== null && retryBox.height >= 44,
        "offline retry link is smaller than the touch target budget",
      );
    }
    await page.setViewportSize(MOBILE_VIEWPORT);
    await page.screenshot({
      path: join(screenshots, "offline-mobile.png"),
      caret: "hide",
    });
    await context.setOffline(false);
    await page.evaluate(() => {
      localStorage.removeItem("stricknani.theme");
    });
    const recovered = await page.goto(`${origin}/app/login`, {
      waitUntil: "networkidle",
    });
    check(
      recovered !== null && recovered.status() === 200,
      "app did not recover after the browser returned online",
    );

    markStage("logging in to check dirty-tab close behavior");
    markStage("waiting for recovered sign-in form");
    await page.locator("#email").waitFor({ state: "visible", timeout: 10_000 });
    await page
      .getByRole("heading", { name: "Sign in", exact: true })
      .waitFor({ state: "visible", timeout: 10_000 });
    markStage("filling recovered sign-in form");
    await page.locator("#email").fill(email);
    await page.locator("#password").fill(password);
    markStage("submitting recovered sign-in form");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    markStage("waiting for authenticated shell after offline recovery");
    await page
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    const beforeEditor = await cacheSnapshot(page);
    const beforeEditorPaths = new Set(Object.values(beforeEditor)[0]);
    await page.goto(`${origin}/app/projects/new`, { waitUntil: "networkidle" });
    await page.locator("#project-name").waitFor({ state: "visible" });
    const editorCache = await cacheSnapshot(page);
    const editorPaths = new Set(Object.values(editorCache)[0]);
    const newEditorChunks = new Set(
      [...editorPaths].filter(
        (path) =>
          !beforeEditorPaths.has(path) &&
          path.startsWith(IMMUTABLE_ASSET_PREFIX) &&
          path.endsWith(".js"),
      ),
    );
    check(
      newEditorChunks.size > 0,
      "project editor did not load its immutable code on demand",
    );
    const draftTitle = "Disposable offline update draft";
    const projectName = page.locator("#project-name");
    await projectName.click();
    await projectName.pressSequentially(draftTitle);
    await page.locator(".dirty-note").waitFor({ state: "visible" });

    const secondTab = await context.newPage();
    watchPage(secondTab);
    await secondTab.goto(`${origin}/app/login`, { waitUntil: "networkidle" });
    await waitForInitialWorker(secondTab, expectedVersion);

    markStage("before update: preparing the synthetic navigation tab");
    const boundary = await context.newPage();
    boundaryTab = boundary;
    const boundaryCdpSession = await context.newCDPSession(boundary);
    boundaryCdp = boundaryCdpSession;
    await boundaryCdpSession.send("ServiceWorker.enable");
    boundaryCdpSession.on(
      "ServiceWorker.workerErrorReported",
      recordWorkerError,
    );
    boundaryCdpSession.on(
      "ServiceWorker.workerVersionUpdated",
      recordWorkerVersions,
    );
    watchPage(boundary);
    let boundaryDialogCount = 0;
    boundary.on("dialog", (dialog: Dialog) => {
      boundaryDialogCount += 1;
      void dialog.dismiss().catch(() => undefined);
    });

    const boundaryMetrics = async (): Promise<[number, number]> => {
      try {
        const metrics: unknown = await boundary.evaluate(() => {
          return [performance.timeOrigin, history.length];
        });
        if (
          !Array.isArray(metrics) ||
          metrics.length !== 2 ||
          typeof metrics[0] !== "number" ||
          typeof metrics[1] !== "number" ||
          !Number.isInteger(metrics[1])
        ) {
          return [0, 0];
        }
        return [metrics[0], metrics[1]];
      } catch {
        return [0, 0];
      }
    };

    const waitForBoundaryState = async (
      predicate: () => boolean,
      stageName: string,
      timeout = 10_000,
    ): Promise<void> => {
      try {
        await boundary.waitForFunction(predicate, undefined, { timeout });
      } catch {
        const [timeOrigin, historyLength] = await boundaryMetrics();
        throw new AcceptanceFailure(
          `${stageName} failed (time-origin: ${timeOrigin.toFixed(0)}; ` +
            `history: ${historyLength}; dialogs: ${boundaryDialogCount})`,
        );
      }
    };

    const boundaryNavigationFailure = async (what: string) => {
      const [timeOrigin, historyLength] = await boundaryMetrics();
      return new AcceptanceFailure(
        `${what} failed (time-origin: ${timeOrigin.toFixed(0)}; ` +
          `history: ${historyLength}; dialogs: ${boundaryDialogCount})`,
      );
    };

    const timeOriginOf = (target: Page): Promise<number> =>
      target.evaluate(() => {
        return performance.timeOrigin;
      });
    const historyLengthOf = (target: Page): Promise<number> =>
      target.evaluate(() => {
        return history.length;
      });

    await boundary.goto(`${origin}/app/login`, { waitUntil: "networkidle" });
    await waitForInitialWorker(boundary, expectedVersion);
    await boundary.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
    await boundary
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    await waitForControlledWorker(boundary, expectedVersion);
    const boundaryTimeOrigin = await timeOriginOf(boundary);
    await boundary
      .getByRole("link", { name: "Yarns", exact: true })
      .first()
      .click();
    await boundary.waitForURL(`${origin}/app/yarns`, { timeout: 10_000 });
    await boundary
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    await boundary
      .getByRole("link", { name: "Projects", exact: true })
      .first()
      .click();
    await boundary.waitForURL(`${origin}/app/projects`, { timeout: 10_000 });
    await boundary
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    const boundaryHistoryLength = await historyLengthOf(boundary);
    check(
      (await timeOriginOf(boundary)) === boundaryTimeOrigin &&
        boundaryHistoryLength >= 2,
      "synthetic navigation tab did not warm SPA history " +
        `(history length: ${boundaryHistoryLength})`,
    );

    markStage("cancelling navigation away from a dirty editor");
    const closeDialogs: string[] = [];
    const editorUrl = page.url();
    page.once("dialog", (dialog: Dialog) => {
      closeDialogs.push(dialog.type());
      void dialog.dismiss().catch(() => undefined);
    });
    try {
      await page.goto(`${origin}/app/login`, { waitUntil: "domcontentloaded" });
    } catch {
      // Chromium reports the canceled cross-document navigation.
    }
    check(
      closeDialogs.length === 1 &&
        closeDialogs[0] === "beforeunload" &&
        !page.isClosed() &&
        page.url() === editorUrl,
      "dirty editor navigation was not cancellable " +
        `(dialog types: ${JSON.stringify(closeDialogs)}, URL preserved: ` +
        `${page.url() === editorUrl})`,
    );
    check(
      (await page.locator("#project-name").inputValue()) === draftTitle,
      "dirty draft disappeared after canceling tab close",
    );

    markStage("preparing a candidate with a missing required asset");
    const failedRoot = join(privateDir, "web-root-failed-install");
    const failedVersion = await prepareBrowserUpdate({
      binary,
      webRoot,
      privateDir,
      fixtureFile,
      stateFile,
      origin,
      port,
      secondRoot: failedRoot,
      versionMarker: "disposable failed install fixture",
      missingAsset: "/app/offline.css",
      runtimeName: "runtime-pwa-failed",
    });
    markStage("verifying the candidate asset is unavailable");
    const missingAsset = await secondTab.request.get(
      `${origin}/app/offline.css`,
    );
    check(
      missingAsset.status() === 503,
      "failed-install fixture did not make the allowlisted asset unavailable",
    );
    markStage("serving the failed-install candidate worker");
    const failedWorkerUrl = await secondTab.request.get(`${origin}/app/sw.js`);
    check(
      failedWorkerUrl.status() === 200 &&
        (await failedWorkerUrl.text()).includes(failedVersion),
      "failed-install candidate worker was not served",
    );
    markStage("asking the browser to install the failed candidate");
    await secondTab.evaluate(async () => {
      const w = window as PwaWindow;
      w.__pwaInstallStates = [];
      const registration =
        await navigator.serviceWorker.getRegistration("/app/");
      if (!registration) return;
      registration.addEventListener("updatefound", () => {
        const installing = registration.installing;
        if (!installing) return;
        const record = () => w.__pwaInstallStates?.push(installing.state);
        record();
        installing.addEventListener("statechange", record);
      });
      await registration.update();
    });
    markStage("waiting for failed candidate rollback");
    const rolledBack = await secondTab.evaluate(() => {
      const w = window as PwaWindow;
      if (w.__pwaInstallStates?.includes("redundant")) {
        return Promise.resolve(true);
      }
      return new Promise<boolean>((resolveRollback) => {
        const registrationPromise =
          navigator.serviceWorker.getRegistration("/app/");
        const timer = setTimeout(() => resolveRollback(false), 15000);
        void registrationPromise.then((registration) => {
          const worker = registration?.installing;
          if (!worker) {
            clearTimeout(timer);
            resolveRollback(false);
            return;
          }
          const checkState = () => {
            if (worker.state !== "redundant") return;
            clearTimeout(timer);
            resolveRollback(true);
          };
          worker.addEventListener("statechange", checkState);
          checkState();
        });
      });
    });
    if (!rolledBack) {
      const state = await secondTab.evaluate(async (prefix: string) => {
        const registration =
          await navigator.serviceWorker.getRegistration("/app/");
        return {
          page: location.pathname,
          ready: document.readyState,
          installStates: (window as PwaWindow).__pwaInstallStates ?? null,
          installing: registration?.installing?.state ?? "",
          waiting: registration?.waiting?.state ?? "",
          active: registration?.active?.state ?? "",
          cachePaths: await Promise.all(
            (await caches.keys())
              .filter((name) => name.startsWith(prefix))
              .map(async (name) => ({
                name,
                paths: (await (await caches.open(name)).keys())
                  .map((request) => new URL(request.url).pathname)
                  .sort(),
              })),
          ),
        };
      }, APP_CACHE_PREFIX);
      throw new AcceptanceFailure(
        "failed candidate rollback was not observed " +
          `(safe state: ${JSON.stringify(state)}; asset responses: ` +
          `${JSON.stringify(serviceWorkerAssetResponses.slice(-12))}; finished: ` +
          `${JSON.stringify(serviceWorkerAssetFinished.slice(-12))})`,
      );
    }
    markStage("verifying failed update retained the active cache");
    const failedSnapshot = await versionSnapshot(secondTab);
    check(
      failedSnapshot.active === expectedVersion &&
        failedSnapshot.waiting === "",
      "failed update changed the active worker or left a waiting worker",
    );
    const failedCaches = await cacheSnapshot(secondTab);
    check(
      Object.keys(failedCaches).length === 1 &&
        (Object.keys(failedCaches)[0] as string).includes(expectedVersion),
      "failed update did not preserve only the active cache",
    );
    check(
      isSubset(Object.values(failedCaches)[0] as string[], expectedAssets),
      "failed update left a path outside the active version allowlist",
    );

    markStage("before update: opening an old-version tab the browser freezes");
    // Frozen background tabs never answer messages. The former update
    // protocol needed every tab to answer, so one frozen tab blocked every
    // update until all tabs were closed.
    await signInThroughApi(secondTab, fixtureFile);
    const frozenTab = await context.newPage();
    frozenTab.on("pageerror", () => {
      pageErrors.push(true);
    });
    const frozenResponse = await frozenTab.goto(`${origin}/app/projects`, {
      waitUntil: "networkidle",
      timeout: 15_000,
    });
    check(
      frozenResponse !== null && frozenResponse.status() === 200,
      "old-version tab did not load",
    );
    await frozenTab
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    await waitForControlledWorker(frozenTab, expectedVersion);
    const frozenTimeOrigin = await timeOriginOf(frozenTab);
    const frozenOldAssets = await loadedImmutableAssets(frozenTab);
    const frozenCdp = await context.newCDPSession(frozenTab);
    await frozenCdp.send("Page.setWebLifecycleState", { state: "frozen" });

    markStage("before update: opening a tab that ignores worker messages");
    // Kept as the original script text: it is an arrow function expression
    // that is never invoked, so (as in the python runner) it is inert.
    await context.addInitScript(`() => {
      if (!new URL(location.href).searchParams
          .has('pwa-unresponsive')) return;
      const prototype = ServiceWorkerContainer.prototype;
      const addEventListener = prototype.addEventListener;
      prototype.addEventListener = function(type, listener, options) {
        if (type === 'message') return;
        return addEventListener.call(this, type, listener, options);
      };
    }`);
    const unresponsiveTab = await context.newPage();
    unresponsiveTab.on("pageerror", () => {
      pageErrors.push(true);
    });
    await unresponsiveTab.goto(`${origin}/app/login?pwa-unresponsive=1`, {
      waitUntil: "networkidle",
    });
    await waitForControlledWorker(unresponsiveTab, expectedVersion);

    markStage("before update: putting the clean tab on an app page");
    await secondTab.goto(`${origin}/app/projects`, {
      waitUntil: "networkidle",
    });
    await secondTab
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    await waitForControlledWorker(secondTab, expectedVersion);
    const pageTimeOrigin = await timeOriginOf(page);
    const secondTimeOrigin = await timeOriginOf(secondTab);

    markStage("restarting the actual Rust fixture with a new worker version");
    const candidateAppVersion =
      "pwa-update-fixture-" +
      sha256Hex(`${expectedAppVersion}:candidate-v1`).slice(0, 24);
    const candidateBuildRoot = await buildCandidateWebRoot(
      privateDir,
      candidateAppVersion,
    );
    const {
      version: candidateBuildVersion,
      appCodeVersion: verifiedCandidateAppVersion,
      assets: candidateAssetList,
    } = readAssetManifest(candidateBuildRoot);
    const candidateAssets = new Set(candidateAssetList);
    check(
      verifiedCandidateAppVersion === candidateAppVersion,
      "second SvelteKit build did not use a distinct compiled version",
    );
    const nextVersion = await prepareBrowserUpdate({
      binary,
      webRoot,
      privateDir,
      fixtureFile,
      stateFile,
      origin,
      port,
      secondRoot,
      candidateBuildRoot,
      runtimeName: "runtime-pwa-success",
    });
    check(
      nextVersion === candidateBuildVersion,
      "Rust fixture did not serve the second build's worker version",
    );
    markStage("checking the served candidate worker version");
    const candidateWorker = await secondTab.request.get(`${origin}/app/sw.js`);
    check(
      candidateWorker.status() === 200 &&
        (await candidateWorker.text()).includes(nextVersion),
      "served update worker has the wrong version",
    );

    markStage("update: letting the candidate take over without asking tabs");
    await secondTab.evaluate(async () => {
      const registration =
        await navigator.serviceWorker.getRegistration("/app/");
      if (!registration) throw new Error("App worker missing");
      await registration.update();
    });
    await waitForActiveWorker(secondTab, nextVersion);
    const takeover = await versionSnapshot(secondTab);
    check(
      takeover.active === nextVersion &&
        takeover.activeAppVersion === candidateAppVersion &&
        takeover.waiting === "",
      "the candidate worker waited instead of taking over " +
        `(active: ${takeover.active}, waiting: ${takeover.waiting}, ` +
        `expected: ${nextVersion})`,
    );
    for (const openTab of [secondTab, page, unresponsiveTab, boundary]) {
      await waitForControlledWorker(openTab, nextVersion);
    }
    check(
      (await timeOriginOf(page)) === pageTimeOrigin &&
        (await timeOriginOf(secondTab)) === secondTimeOrigin &&
        (await timeOriginOf(boundary)) === boundaryTimeOrigin,
      "the worker takeover reloaded an open tab",
    );
    check(
      (await page.locator("#project-name").inputValue()) === draftTitle,
      "the worker takeover changed the unsaved project draft",
    );

    await signInThroughApi(secondTab, fixtureFile);

    markStage("update: checking retained caches after the takeover");
    const takeoverCaches = await cacheSnapshot(secondTab);
    check(
      `${APP_CACHE_PREFIX}${expectedVersion}` in takeoverCaches &&
        `${APP_CACHE_PREFIX}${nextVersion}` in takeoverCaches &&
        Object.keys(takeoverCaches).length === 2,
      "the takeover did not keep exactly the previous and the new cache " +
        `(caches: ${JSON.stringify(Object.keys(takeoverCaches).sort())})`,
    );
    check(
      setsEqual(
        new Set(takeoverCaches[`${APP_CACHE_PREFIX}${nextVersion}`]),
        REQUIRED_OFFLINE_ASSETS,
      ),
      "the candidate install cached more than the offline assets",
    );
    check(
      (await secondTab.evaluate(() => {
        return caches.has("stricknani-app-update-state-v1");
      })) === false,
      "the legacy update-state cache survived activation",
    );

    markStage("making a cached immutable route asset unavailable");
    const cachedImmutableAsset = [
      ...new Set([...beforeEditorPaths, ...newEditorChunks]),
    ]
      .filter((asset) => candidateAssets.has(asset))
      .sort()
      .find((asset) => {
        if (
          !asset.startsWith(IMMUTABLE_ASSET_PREFIX) ||
          !asset.endsWith(".js")
        ) {
          return false;
        }
        const relative = removePrefix(asset, "/app/");
        const current = join(webRoot, relative);
        const candidate = join(candidateBuildRoot, relative);
        return (
          existsSync(current) &&
          existsSync(candidate) &&
          sha256Hex(readFileSync(current)) ===
            sha256Hex(readFileSync(candidate))
        );
      });
    check(
      cachedImmutableAsset !== undefined,
      "candidate build has no unchanged cached immutable asset",
    );
    const unavailableCandidateAsset = join(
      secondRoot,
      removePrefix(cachedImmutableAsset, "/app/"),
    );
    check(
      existsSync(unavailableCandidateAsset) &&
        statSync(unavailableCandidateAsset).isFile(),
      "cached editor asset is absent from the update candidate",
    );
    rmSync(unavailableCandidateAsset);
    const directMissingAsset = await secondTab.request.get(
      `${origin}${cachedImmutableAsset}`,
      { timeout: 10_000 },
    );
    check(
      directMissingAsset.status() >= 400,
      "candidate still serves the selected cached editor asset",
    );

    markStage("update: detecting the new version in an open tab");
    // SvelteKit checks version.json when a tab regains focus.
    await secondTab.evaluate(() => {
      window.dispatchEvent(new Event("focus"));
    });
    await secondTab
      .getByText("New version ready", { exact: true })
      .waitFor({ state: "visible", timeout: 10_000 });
    check(
      (await timeOriginOf(secondTab)) === secondTimeOrigin,
      "detecting the new version reloaded the tab by itself",
    );
    await secondTab.screenshot({
      path: join(screenshots, "update-ready-mobile.png"),
      caret: "hide",
    });

    markStage("update boundary: detecting readiness in the synthetic tab");
    await boundary.evaluate(() => {
      window.dispatchEvent(new Event("focus"));
    });
    await boundary
      .getByText("New version ready", { exact: true })
      .waitFor({ state: "visible", timeout: 10_000 });
    const boundaryDialogsBefore = boundaryDialogCount;

    markStage("update boundary: browser Back to the warmed Yarn route");
    const [originBeforePopstate, historyBeforePopstate] =
      await boundaryMetrics();
    try {
      await boundary.goBack();
    } catch {
      throw await boundaryNavigationFailure("browser Back");
    }
    await waitForBoundaryState(
      () => location.pathname === "/app/yarns",
      "browser Back to the warmed route",
    );
    await boundary
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    const originAfterBack = await timeOriginOf(boundary);
    const historyAfterBack = await historyLengthOf(boundary);

    markStage("update boundary: browser Forward to the warmed Projects route");
    try {
      await boundary.goForward();
    } catch {
      throw await boundaryNavigationFailure("browser Forward");
    }
    await waitForBoundaryState(
      () => location.pathname === "/app/projects",
      "browser Forward to the warmed route",
    );
    await boundary
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    const originAfterForward = await timeOriginOf(boundary);
    const historyAfterForward = await historyLengthOf(boundary);
    const backForwardDialogDelta = boundaryDialogCount - boundaryDialogsBefore;
    check(
      originAfterBack === originBeforePopstate &&
        originAfterForward === originBeforePopstate &&
        historyAfterBack === historyBeforePopstate &&
        historyAfterForward === historyBeforePopstate &&
        backForwardDialogDelta === 0,
      "update boundary changed native Back/Forward state " +
        `(time-origin deltas: ${originAfterBack - originBeforePopstate}, ` +
        `${originAfterForward - originBeforePopstate}; history deltas: ` +
        `${historyAfterBack - historyBeforePopstate}, ` +
        `${historyAfterForward - historyBeforePopstate}; ` +
        `dialogs: ${backForwardDialogDelta})`,
    );

    markStage("update boundary: clicking a same-document hash link");
    await boundary.evaluate(() => {
      const main = document.querySelector("main#main-content");
      if (!(main instanceof HTMLElement)) {
        throw new Error("main#main-content is missing");
      }
      const heading = main.querySelector(".page-header");
      if (!(heading instanceof HTMLElement)) {
        throw new Error("main#main-content page heading is missing");
      }
      const section = document.createElement("section");
      section.id = "pwa-update-boundary-links";
      const target = document.createElement("span");
      target.id = "pwa-update-boundary-hash-target";
      target.textContent = "Navigation boundary target";
      const link = document.createElement("a");
      link.id = "pwa-update-boundary-hash-link";
      link.href = "#pwa-update-boundary-hash-target";
      link.textContent = "Navigation boundary";
      section.append(link, target);
      heading.insertAdjacentElement("afterend", section);
    });
    const hashOriginBefore = await timeOriginOf(boundary);
    const hashHistoryBefore = await historyLengthOf(boundary);
    const hashDialogsBefore = boundaryDialogCount;
    const hashLink = boundary.locator("#pwa-update-boundary-hash-link");
    await hashLink.scrollIntoViewIfNeeded();
    const hashHitTest = await boundary.evaluate(() => {
      const link = document.querySelector("#pwa-update-boundary-hash-link");
      if (!(link instanceof HTMLElement)) {
        return { hit: false, x: 0, y: 0, hitTag: "" };
      }
      const rect = link.getBoundingClientRect();
      const x = rect.left + rect.width / 2;
      const y = rect.top + rect.height / 2;
      const hit = document.elementFromPoint(x, y);
      return {
        hit: hit === link || link.contains(hit),
        x,
        y,
        hitTag: hit?.tagName ?? "",
      };
    });
    if (hashHitTest.hit !== true) {
      throw new AcceptanceFailure(
        "same-document hash link center is covered " +
          `(hit: ${hashHitTest.hit}; x: ${hashHitTest.x.toFixed(0)}; ` +
          `y: ${hashHitTest.y.toFixed(0)})`,
      );
    }
    await hashLink.click();
    await waitForBoundaryState(
      () => location.hash === "#pwa-update-boundary-hash-target",
      "same-document hash navigation",
      5_000,
    );
    const hashOriginAfter = await timeOriginOf(boundary);
    const hashHistoryAfter = await historyLengthOf(boundary);
    const hashDialogDelta = boundaryDialogCount - hashDialogsBefore;
    check(
      hashOriginAfter === hashOriginBefore &&
        hashHistoryAfter === hashHistoryBefore + 1 &&
        hashDialogDelta === 0,
      "same-document hash navigation changed native update state " +
        `(time-origin delta: ${hashOriginAfter - hashOriginBefore}; ` +
        `history delta: ${hashHistoryAfter - hashHistoryBefore}; ` +
        `dialogs: ${hashDialogDelta})`,
    );

    markStage("update boundary: following an external loopback link");
    const originParts = new URL(origin);
    const externalHost =
      originParts.hostname !== "localhost" ? "localhost" : "127.0.0.1";
    const externalUrl = `${originParts.protocol}//${externalHost}:${port}/pwa-boundary`;
    let externalRouteCount = 0;
    const fulfillBoundaryDestination = async (route: Route): Promise<void> => {
      externalRouteCount += 1;
      await route.fulfill({
        status: 200,
        contentType: "text/html; charset=utf-8",
        body:
          "<!doctype html><html><head><title>Controlled loopback " +
          "destination</title></head><body><h1>Controlled loopback " +
          "destination</h1></body></html>",
      });
    };
    await boundary.route(externalUrl, fulfillBoundaryDestination);
    await boundary.evaluate((url: string) => {
      const main = document.querySelector("main#main-content");
      const section = document.querySelector("#pwa-update-boundary-links");
      if (
        !(main instanceof HTMLElement) ||
        !(section instanceof HTMLElement) ||
        !main.contains(section)
      ) {
        throw new Error("boundary links are outside main#main-content");
      }
      const link = document.createElement("a");
      link.id = "pwa-update-boundary-external-link";
      link.href = url;
      link.textContent = "External loopback destination";
      section.append(link);
    }, externalUrl);
    await boundary.addInitScript(`(() => {
      let visible = false;
      const sample = () => {
        const now = [...document.querySelectorAll('[role="status"]')]
          .some(node => node.textContent?.trim()
            === 'Stricknani was updated.');
        if (now && !visible) {
          const key = 'pwa-updated-toast-count';
          sessionStorage.setItem(
            key, String(Number(sessionStorage.getItem(key) || 0) + 1)
          );
        }
        visible = now;
      };
      new MutationObserver(sample).observe(document, {
        childList: true, subtree: true, characterData: true,
      });
      sample();
    })();`);
    const externalOriginBefore = await timeOriginOf(boundary);
    const externalHistoryBefore = await historyLengthOf(boundary);
    const externalDialogsBefore = boundaryDialogCount;
    let externalResponse: Response | null;
    try {
      [externalResponse] = await Promise.all([
        boundary.waitForNavigation({
          waitUntil: "domcontentloaded",
          timeout: 10_000,
        }),
        boundary.locator("#pwa-update-boundary-external-link").click(),
      ]);
    } catch {
      const [timeOrigin, historyLength] = await boundaryMetrics();
      throw new AcceptanceFailure(
        `external loopback navigation failed (time-origin: ${timeOrigin.toFixed(0)}; ` +
          `history: ${historyLength}; route hits: ${externalRouteCount}; ` +
          `dialogs: ${boundaryDialogCount})`,
      );
    }
    await boundary
      .getByRole("heading", {
        name: "Controlled loopback destination",
        exact: true,
      })
      .waitFor({ state: "visible", timeout: 5_000 });
    const externalDestinationReached = await boundary.evaluate(
      (host: string) => {
        return (
          location.hostname === host && location.pathname === "/pwa-boundary"
        );
      },
      externalHost,
    );
    const externalOriginAfter = await timeOriginOf(boundary);
    const externalHistoryAfter = await historyLengthOf(boundary);
    const externalDialogDelta = boundaryDialogCount - externalDialogsBefore;
    // Playwright sometimes reports no Response object for a fulfilled
    // navigation; the single route hit and the rendered destination prove it
    // was served, so only reject a non-200 Response.
    const externalResponseOk =
      externalResponse === null || externalResponse.status() === 200;
    check(
      externalResponseOk &&
        externalRouteCount === 1 &&
        externalDestinationReached &&
        externalOriginAfter !== externalOriginBefore &&
        externalHistoryAfter === externalHistoryBefore + 1 &&
        externalDialogDelta === 0,
      "external loopback navigation did not follow normal browser history " +
        `(response: ${externalResponseOk}; route hits: ${externalRouteCount}; ` +
        `destination: ${externalDestinationReached}; time-origin changed: ` +
        `${externalOriginAfter !== externalOriginBefore}; history delta: ` +
        `${externalHistoryAfter - externalHistoryBefore}; ` +
        `dialogs: ${externalDialogDelta})`,
    );

    markStage("update boundary: browser Back from the loopback destination");
    try {
      await boundary.goBack();
    } catch {
      throw await boundaryNavigationFailure(
        "browser Back from the destination",
      );
    }
    await waitForBoundaryState(
      () =>
        location.pathname === "/app/projects" &&
        location.hash === "#pwa-update-boundary-hash-target",
      "browser Back from the destination",
    );
    await boundary
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    const externalBackInApp = await boundary.evaluate(() => {
      return (
        location.pathname === "/app/projects" &&
        location.hash === "#pwa-update-boundary-hash-target"
      );
    });

    markStage("update boundary: browser Forward to the loopback destination");
    try {
      await boundary.goForward();
    } catch {
      throw await boundaryNavigationFailure(
        "browser Forward to the destination",
      );
    }
    await waitForBoundaryState(
      () => location.pathname === "/pwa-boundary",
      "browser Forward to the destination",
    );
    await boundary
      .getByRole("heading", {
        name: "Controlled loopback destination",
        exact: true,
      })
      .waitFor({ state: "visible", timeout: 5_000 });
    const externalForwardToDestination = await boundary.evaluate(() => {
      return location.pathname === "/pwa-boundary";
    });
    try {
      await boundary.goBack();
    } catch {
      throw await boundaryNavigationFailure("browser Back to the app");
    }
    await waitForBoundaryState(
      () =>
        location.pathname === "/app/projects" &&
        location.hash === "#pwa-update-boundary-hash-target",
      "browser Back to the app",
    );
    // The first full load of the candidate build in this tab shows the
    // one-time toast; the shared seen-version marker keeps every later load,
    // in this tab and in other tabs, quiet.
    check(
      (await boundary.evaluate(() => {
        return sessionStorage.getItem("pwa-updated-toast-count");
      })) === "1",
      "the boundary tab did not show the one-time updated toast exactly once",
    );
    check(
      externalBackInApp &&
        externalForwardToDestination &&
        boundaryDialogCount === externalDialogsBefore,
      "loopback Back/Forward opened an update prompt or lost history " +
        `(back: ${externalBackInApp}; forward: ${externalForwardToDestination}; ` +
        `dialogs: ${boundaryDialogCount - externalDialogsBefore})`,
    );
    await boundary.unroute(externalUrl, fulfillBoundaryDestination);

    markStage("update: refusing to reload a dirty tab");
    await page.evaluate(() => {
      window.dispatchEvent(new Event("focus"));
    });
    await page
      .getByText("New version ready", { exact: true })
      .waitFor({ state: "visible", timeout: 10_000 });
    await page.getByRole("button", { name: "Reload", exact: true }).click();
    await page
      .getByText("Save your changes first, then reload.", { exact: true })
      .waitFor({ state: "visible", timeout: 5_000 });
    await page.screenshot({
      path: join(screenshots, "update-blocked-dirty-mobile.png"),
      caret: "hide",
    });
    check(
      (await timeOriginOf(page)) === pageTimeOrigin &&
        (await page.locator("#project-name").inputValue()) === draftTitle,
      "the reload chip reloaded a dirty tab or lost its draft",
    );

    markStage("update: loading the new version on a clean navigation");
    const updateDialogs: string[] = [];
    secondTab.on("dialog", (dialog: Dialog) => {
      updateDialogs.push(dialog.type());
    });
    await secondTab
      .getByRole("link", { name: "Yarns", exact: true })
      .first()
      .click();
    await secondTab.waitForURL(`${origin}/app/yarns`, { timeout: 15_000 });
    await waitAsync(
      secondTab,
      () =>
        secondTab.evaluate((previous: number) => {
          return performance.timeOrigin > previous;
        }, secondTimeOrigin),
      "a full load after the clean navigation",
    );
    await secondTab
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    check(updateDialogs.length === 0, "the update navigation opened a dialog");
    const newAssets = await loadedImmutableAssets(secondTab);
    check(
      newAssets.size > 0 && isSubset(newAssets, candidateAssets),
      "the clean navigation did not load the candidate build",
    );
    check(
      (await secondTab
        .getByText("New version ready", { exact: true })
        .count()) === 0,
      "the reloaded tab still announces an update",
    );
    // The toast is keyed by a localStorage "seen version" shared by every tab
    // of the origin, so the boundary tab already consumed it.
    check(
      (await secondTab
        .getByText("Stricknani was updated.", { exact: true })
        .count()) === 0,
      "a later tab repeated the one-time updated toast",
    );
    check(
      await secondTab.evaluate((version: string) => {
        return localStorage.getItem("stricknani.seen-app-version") === version;
      }, candidateAppVersion),
      "the shared seen-version marker was not set to the candidate build",
    );
    await secondTab.screenshot({
      path: join(screenshots, "updated-mobile.png"),
      caret: "hide",
    });

    markStage("update: loading the new version once the draft is clean");
    await projectName.fill("");
    await page
      .locator(".dirty-note")
      .waitFor({ state: "hidden", timeout: 5_000 });
    await page
      .getByRole("link", { name: "Projects", exact: true })
      .first()
      .click();
    await page.waitForURL(`${origin}/app/projects`, { timeout: 15_000 });
    await waitAsync(
      page,
      () =>
        page.evaluate((previous: number) => {
          return performance.timeOrigin > previous;
        }, pageTimeOrigin),
      "a full load after the draft was cleared",
    );
    await page
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    check(
      isSubset(await loadedImmutableAssets(page), candidateAssets),
      "the cleaned tab did not load the candidate build",
    );

    markStage("update: thawing the frozen old-version tab");
    await frozenCdp.send("Page.setWebLifecycleState", { state: "active" });
    check(
      (await timeOriginOf(frozenTab)) === frozenTimeOrigin,
      "the frozen tab was reloaded while it was frozen",
    );
    await waitForControlledWorker(frozenTab, nextVersion);
    // The candidate build differs only in its version name, so its chunks
    // match the old ones. Remove one of the old tab's cached chunks from the
    // server to prove the tab still gets it from the retained
    // previous-version cache.
    const oldCachePaths = new Set(
      (await cacheSnapshot(frozenTab))[
        `${APP_CACHE_PREFIX}${expectedVersion}`
      ] ?? [],
    );
    const oldOnlyAsset = [...frozenOldAssets]
      .filter((asset) => oldCachePaths.has(asset))
      .sort()
      .find(
        (asset) =>
          asset.endsWith(".js") &&
          asset !== cachedImmutableAsset &&
          existsSync(join(webRoot, removePrefix(asset, "/app/"))),
      );
    check(
      oldOnlyAsset !== undefined,
      "the old tab has no chunk in the retained previous-version cache",
    );
    rmSync(join(secondRoot, removePrefix(oldOnlyAsset, "/app/")), {
      force: true,
    });
    check(
      (await frozenTab.request.get(`${origin}${oldOnlyAsset}`)).status() >= 400,
      "the server still serves the removed previous-version chunk",
    );
    const oldChunk = await frozenTab.evaluate(async (asset: string) => {
      const fetched = await fetch(asset, { cache: "no-store" });
      const bytes = await fetched.arrayBuffer();
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      return {
        status: fetched.status,
        sha256: Array.from(new Uint8Array(digest))
          .map((value) => value.toString(16).padStart(2, "0"))
          .join(""),
      };
    }, oldOnlyAsset);
    check(
      oldChunk.status === 200 &&
        oldChunk.sha256 ===
          sha256Hex(
            readFileSync(join(webRoot, removePrefix(oldOnlyAsset, "/app/"))),
          ),
      "the old tab could not load its previous-version chunk",
    );
    await frozenTab.evaluate(() => {
      window.dispatchEvent(new Event("focus"));
    });
    await frozenTab
      .getByText("New version ready", { exact: true })
      .waitFor({ state: "visible", timeout: 10_000 });
    await frozenTab
      .getByRole("link", { name: "Yarns", exact: true })
      .first()
      .click();
    await frozenTab.waitForURL(`${origin}/app/yarns`, { timeout: 15_000 });
    await waitAsync(
      frozenTab,
      () =>
        frozenTab.evaluate((previous: number) => {
          return performance.timeOrigin > previous;
        }, frozenTimeOrigin),
      "a full load of the thawed tab",
    );
    await frozenTab
      .locator(".app-shell")
      .waitFor({ state: "visible", timeout: 10_000 });
    check(
      isSubset(await loadedImmutableAssets(frozenTab), candidateAssets),
      "the thawed tab did not move to the candidate build",
    );
    await frozenTab.close();
    await unresponsiveTab.close();

    markStage("update: preparing a network-failure fallback tab");
    const assetResponseStart = serviceWorkerAssetResponses.length;
    const networkFailureTab = await context.newPage();
    const networkDialogs: string[] = [];
    networkFailureTab.on("dialog", (dialog: Dialog) => {
      networkDialogs.push(dialog.type());
      void dialog.dismiss().catch(() => undefined);
    });
    watchPage(networkFailureTab);
    const networkPage = await networkFailureTab.goto(`${origin}/app/login`, {
      waitUntil: "domcontentloaded",
      timeout: 15_000,
    });
    check(
      networkPage !== null && networkPage.status() === 200,
      "network-failure fixture tab did not load the app",
    );
    await waitForControlledWorker(networkFailureTab, nextVersion);

    markStage("forcing a network error for a retained immutable asset");
    await networkFailureTab.bringToFront();
    const visibilityState = await networkFailureTab.evaluate(() => {
      return document.visibilityState;
    });
    check(
      visibilityState === "visible" && networkDialogs.length === 0,
      "network-failure fixture tab is not foregrounded or has a dialog",
    );
    const oldCacheName = `${APP_CACHE_PREFIX}${expectedVersion}`;
    const candidateCacheName = `${APP_CACHE_PREFIX}${nextVersion}`;
    const assetArg = {
      asset: cachedImmutableAsset,
      cacheName: candidateCacheName,
    };
    let fallbackState: Record<string, unknown>;
    await context.setOffline(true);
    try {
      const pageState = await networkFailureTab.evaluate(() => {
        return {
          offline: navigator.onLine === false,
          controlled: Boolean(
            navigator.serviceWorker.controller?.scriptURL.endsWith(
              "/app/sw.js",
            ),
          ),
        };
      });
      check(
        pageState.offline === true && pageState.controlled === true,
        "browser page was not responsive and controlled after offline mode",
      );
      markStage("clearing the candidate cache entry for network fallback");
      await networkFailureTab.evaluate(
        async ({ asset, cacheName }: { asset: string; cacheName: string }) => {
          const operation = (async () => {
            return await (
              await caches.open(cacheName)
            ).delete(asset, { ignoreSearch: false });
          })();
          return await Promise.race([
            operation,
            new Promise<null>((resolveTimeout) =>
              setTimeout(() => resolveTimeout(null), 3000),
            ),
          ]);
        },
        assetArg,
      );
      const previousState = await networkFailureTab.evaluate(
        async ({
          asset,
          oldCache,
          candidateCache,
        }: {
          asset: string;
          oldCache: string;
          candidateCache: string;
        }) => {
          const operation = (async () => {
            const previous = await caches.open(oldCache);
            const candidate = await caches.open(candidateCache);
            return {
              cacheOperationsSettled: true,
              oldEntryPresent: Boolean(
                await previous.match(asset, { ignoreSearch: false }),
              ),
              candidateMissBeforeFetch: !(await candidate.match(asset, {
                ignoreSearch: false,
              })),
            };
          })();
          return await Promise.race([
            operation,
            new Promise<{ cacheOperationsSettled: false }>((resolveTimeout) =>
              setTimeout(
                () => resolveTimeout({ cacheOperationsSettled: false }),
                3000,
              ),
            ),
          ]);
        },
        {
          asset: cachedImmutableAsset,
          oldCache: oldCacheName,
          candidateCache: candidateCacheName,
        },
      );
      check(
        previousState.cacheOperationsSettled === true &&
          "oldEntryPresent" in previousState &&
          previousState.oldEntryPresent === true &&
          previousState.candidateMissBeforeFetch === true,
        "network fallback fixture did not isolate the retained entry",
      );
      markStage("requesting the retained asset while browser networking fails");
      const assetFailureStart = serviceWorkerAssetFailures.length;
      const fetchState = await networkFailureTab.evaluate(
        async ({ asset, oldCache }: { asset: string; oldCache: string }) => {
          const retained = await (
            await caches.open(oldCache)
          ).match(asset, { ignoreSearch: false });
          const hash = async (bytes: ArrayBuffer) =>
            Array.from(
              new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
            )
              .map((value) => value.toString(16).padStart(2, "0"))
              .join("");
          const retainedHash = retained
            ? await hash(await retained.arrayBuffer())
            : "";
          const controller = new AbortController();
          type FetchOutcome = {
            fetchSettled: boolean;
            status: number;
            byteLength: number;
            matchesRetainedBytes: boolean;
            fetchErrorName: string;
          };
          const request: Promise<FetchOutcome> = fetch(asset, {
            cache: "no-store",
            signal: controller.signal,
          })
            .then(async (fetched) => {
              const bytes = await fetched.arrayBuffer();
              return {
                fetchSettled: true,
                status: fetched.status,
                byteLength: bytes.byteLength,
                matchesRetainedBytes:
                  retainedHash !== "" && (await hash(bytes)) === retainedHash,
                fetchErrorName: "",
              };
            })
            .catch((error: { name?: string } | null): FetchOutcome => ({
              fetchSettled: true,
              status: 0,
              byteLength: 0,
              matchesRetainedBytes: false,
              fetchErrorName: error?.name ?? "unknown",
            }));
          const result = await Promise.race([
            request,
            new Promise<null>((resolveTimeout) =>
              setTimeout(() => resolveTimeout(null), 8000),
            ),
          ]);
          if (result === null) {
            controller.abort();
            return {
              fetchSettled: false,
              status: 0,
              byteLength: 0,
              matchesRetainedBytes: false,
              fetchErrorName: "timeout",
            } as FetchOutcome;
          }
          return result;
        },
        { asset: cachedImmutableAsset, oldCache: oldCacheName },
      );
      check(
        fetchState.fetchSettled === true,
        "controlled network-failure request did not settle",
      );
      markStage("waiting for the retained asset cache promotion");
      await waitAsync(
        networkFailureTab,
        () =>
          networkFailureTab.evaluate(
            async ({
              asset,
              cacheName,
            }: {
              asset: string;
              cacheName: string;
            }) =>
              Boolean(
                await await Promise.race([
                  (async () =>
                    Boolean(
                      await (
                        await caches.open(cacheName)
                      ).match(asset, { ignoreSearch: false }),
                    ))(),
                  new Promise<boolean>((resolveTimeout) =>
                    setTimeout(() => resolveTimeout(false), 500),
                  ),
                ]),
              ),
            assetArg,
          ),
        "network-failure asset cache promotion",
        5_000,
      );
      const workerFailureCount =
        serviceWorkerAssetFailures.length - assetFailureStart;
      const workerResponseCount = serviceWorkerAssetResponses
        .slice(assetResponseStart)
        .filter((entry) => entry === `${cachedImmutableAsset}:200`).length;
      const promotedState = await networkFailureTab.evaluate(
        async ({ asset, cacheName }: { asset: string; cacheName: string }) => {
          const operation = (async () => {
            const candidate = await caches.open(cacheName);
            const promoted = Boolean(
              await candidate.match(asset, { ignoreSearch: false }),
            );
            const clearedForHttpFallback =
              promoted &&
              (await candidate.delete(asset, { ignoreSearch: false }));
            return {
              cacheOperationsSettled: true,
              promoted,
              clearedForHttpFallback,
            };
          })();
          return await Promise.race([
            operation,
            new Promise<{
              cacheOperationsSettled: boolean;
              promoted: boolean;
              clearedForHttpFallback: boolean;
            }>((resolveTimeout) =>
              setTimeout(
                () =>
                  resolveTimeout({
                    cacheOperationsSettled: false,
                    promoted: false,
                    clearedForHttpFallback: false,
                  }),
                3000,
              ),
            ),
          ]);
        },
        assetArg,
      );
      fallbackState = {
        ...pageState,
        ...previousState,
        ...fetchState,
        ...promotedState,
        workerFailureCount,
        workerResponseCount,
        offlineObserved: pageState.offline,
        controlledByAppWorker: pageState.controlled,
        diagnosticTimeout: false,
        timeoutStage: "",
      };
    } finally {
      await context.setOffline(false);
    }
    const byteLength = fallbackState.byteLength;
    const workerFailures = fallbackState.workerFailureCount;
    check(
      fallbackState.offlineObserved === true &&
        fallbackState.oldEntryPresent === true &&
        fallbackState.candidateMissBeforeFetch === true &&
        fallbackState.fetchSettled === true &&
        fallbackState.status === 200 &&
        typeof byteLength === "number" &&
        Number.isInteger(byteLength) &&
        byteLength > 0 &&
        fallbackState.matchesRetainedBytes === true &&
        typeof workerFailures === "number" &&
        workerFailures > 0 &&
        fallbackState.promoted === true &&
        fallbackState.cacheOperationsSettled === true &&
        fallbackState.clearedForHttpFallback === true,
      "network-failure fallback was not promoted into the candidate cache " +
        `(safe state: {timeout: ${fallbackState.diagnosticTimeout}, ` +
        `stage: ${fallbackState.timeoutStage}, ` +
        `settled: ${fallbackState.fetchSettled}, ` +
        `status: ${fallbackState.status}, ` +
        `error: ${fallbackState.fetchErrorName}, ` +
        `controlled: ${fallbackState.controlledByAppWorker}, ` +
        `oldEntry: ${fallbackState.oldEntryPresent}, ` +
        `workerFailures: ${fallbackState.workerFailureCount}, ` +
        `workerResponses: ${fallbackState.workerResponseCount}, ` +
        `dialog: ${networkDialogs.length > 0}, ` +
        `promoted: ${fallbackState.promoted}})`,
    );
    markStage("checking HTTP-error fallback from a controlled app page");
    const missingAssetResponse = await networkFailureTab.request.get(
      `${origin}${cachedImmutableAsset}`,
      { timeout: 10_000 },
    );
    check(
      missingAssetResponse.status() >= 400,
      "HTTP-error fallback fixture unexpectedly serves the immutable asset",
    );
    const httpFallbackState = await networkFailureTab.evaluate(
      async ({ asset, oldCache }: { asset: string; oldCache: string }) => {
        const retained = await (
          await caches.open(oldCache)
        ).match(asset, { ignoreSearch: false });
        const hash = async (bytes: ArrayBuffer) =>
          Array.from(
            new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
          )
            .map((value) => value.toString(16).padStart(2, "0"))
            .join("");
        const retainedHash = retained
          ? await hash(await retained.arrayBuffer())
          : "";
        try {
          const fetched = await fetch(asset, { cache: "no-store" });
          const bytes = await fetched.arrayBuffer();
          return {
            settled: true,
            status: fetched.status,
            byteLength: bytes.byteLength,
            matchesRetainedBytes:
              retainedHash !== "" && (await hash(bytes)) === retainedHash,
          };
        } catch {
          return {
            settled: true,
            status: 0,
            byteLength: 0,
            matchesRetainedBytes: false,
          };
        }
      },
      { asset: cachedImmutableAsset, oldCache: oldCacheName },
    );
    check(
      httpFallbackState.settled === true &&
        httpFallbackState.status === 200 &&
        Number.isInteger(httpFallbackState.byteLength) &&
        httpFallbackState.byteLength > 0 &&
        httpFallbackState.matchesRetainedBytes === true,
      "HTTP-error fallback did not return the retained old-cache bytes",
    );
    await waitAsync(
      networkFailureTab,
      () =>
        networkFailureTab.evaluate(
          async ({ asset, cacheName }: { asset: string; cacheName: string }) =>
            Boolean(
              await (
                await caches.open(cacheName)
              ).match(asset, { ignoreSearch: false }),
            ),
          assetArg,
        ),
      "HTTP-error asset promotion into the candidate cache",
      10_000,
    );
    await networkFailureTab.close();

    markStage("update: pruning to the newest three app versions");
    const laterVersions: string[] = [];
    for (const index of [3, 4]) {
      const laterVersion = await prepareBrowserUpdate({
        binary,
        webRoot: candidateBuildRoot,
        privateDir,
        fixtureFile,
        stateFile,
        origin,
        port,
        secondRoot: join(privateDir, `web-root-v${index}`),
        versionMarker: `disposable cache retention fixture ${index}`,
        runtimeName: `runtime-pwa-v${index}`,
      });
      laterVersions.push(laterVersion);
      await page.evaluate(async () => {
        const registration =
          await navigator.serviceWorker.getRegistration("/app/");
        await registration?.update();
      });
      await waitForActiveWorker(page, laterVersion);
    }
    const retainedCaches = await cacheSnapshot(page);
    check(
      setsEqual(
        new Set(Object.keys(retainedCaches)),
        new Set(
          [nextVersion, ...laterVersions].map(
            (version) => `${APP_CACHE_PREFIX}${version}`,
          ),
        ),
      ),
      "the worker did not keep exactly the newest three app caches " +
        `(caches: ${JSON.stringify(Object.keys(retainedCaches).sort())})`,
    );
    check(
      activationWorkerErrors.length === 0,
      "browser reported an app worker error during updates " +
        `(sanitized class/line: ${JSON.stringify(activationWorkerErrors.slice(-8))})`,
    );

    markStage("checking online recovery and console policy");
    check(pageErrors.length === 0, "browser reported a page exception");
    check(cspErrors.length === 0, "browser reported a CSP violation");
  } finally {
    if (boundaryCdp !== null) {
      try {
        await (boundaryCdp as CDPSession).detach();
      } catch {
        // already detached
      }
    }
    if (boundaryTab !== null && !(boundaryTab as Page).isClosed()) {
      try {
        await (boundaryTab as Page).close();
      } catch {
        // already closing
      }
    }
    try {
      await closeBrowser(browser);
    } catch {
      // already closed
    }
  }
}
