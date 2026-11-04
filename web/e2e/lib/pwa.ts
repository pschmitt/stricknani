// Pure and filesystem helpers of the PWA acceptance journey (port of the
// non-browser parts of ci/stricknani/web_pwa_browser.py).
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import {
  accessSync,
  appendFileSync,
  chmodSync,
  constants,
  cpSync,
  existsSync,
  readdirSync,
  readFileSync,
  realpathSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { basename, delimiter, join } from "node:path";

import { AcceptanceFailure } from "./common";
import { REPO_ROOT } from "./fixture";

export const APP_CACHE_PREFIX = "stricknani-app-shell-";
export const IMMUTABLE_ASSET_PREFIX = "/app/_app/immutable/";
export const REQUIRED_OFFLINE_ASSETS: ReadonlySet<string> = new Set([
  "/app/manifest.webmanifest",
  "/app/offline.html",
  "/app/offline.css",
  "/app/offline-theme.js",
  "/app/favicon.svg",
  "/app/icons/icon-192.png",
  "/app/icons/icon-512.png",
  // The manifest's maskable icon is part of the offline install since the
  // Winky Pom icon set (9e5f3b20); static/service-worker.js precaches it.
  "/app/icons/icon-maskable-512.png",
]);

export function sha256Hex(data: string | Uint8Array): string {
  return createHash("sha256").update(data).digest("hex");
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function removePrefix(value: string, prefix: string): string {
  return value.startsWith(prefix) ? value.slice(prefix.length) : value;
}

export function isSubset(
  subset: Iterable<string>,
  superset: { has(value: string): boolean },
): boolean {
  for (const value of subset) {
    if (!superset.has(value)) return false;
  }
  return true;
}

export function setsEqual(a: ReadonlySet<string>, b: ReadonlySet<string>) {
  return a.size === b.size && isSubset(a, b);
}

export function countOccurrences(text: string, needle: string): number {
  return text.split(needle).length - 1;
}

/** Page-like object with the single method the poll loop needs. */
export interface PollingPage {
  waitForTimeout(timeoutMs: number): Promise<void>;
}

/**
 * Poll an awaited browser condition. `waitForFunction` treats unresolved
 * Promises as truthy, so asynchronous conditions are re-evaluated until the
 * resolved value is exactly `true`.
 */
export async function waitForAsyncCondition(
  page: PollingPage,
  poll: () => Promise<unknown>,
  description: string,
  timeoutMs = 15_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if ((await poll()) === true) return;
    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      throw new AcceptanceFailure(`timed out waiting for ${description}`);
    }
    await page.waitForTimeout(
      Math.min(100, Math.max(1, Math.floor(remaining))),
    );
  }
}

export function productionWorkerTemplate(): string {
  return readFileSync(join(REPO_ROOT, "web/static/service-worker.js"), "utf8");
}

function readJson(file: string, message: string): unknown {
  try {
    return JSON.parse(readFileSync(file, "utf8")) as unknown;
  } catch {
    throw new AcceptanceFailure(message);
  }
}

function readAppCodeVersion(webRoot: string): string {
  const data = readJson(
    join(webRoot, "_app/version.json"),
    "compiled app version is unavailable",
  );
  const version = isRecord(data) ? data.version : undefined;
  if (typeof version !== "string" || !version || version.length > 128) {
    throw new AcceptanceFailure("compiled app version is invalid");
  }
  return version;
}

/** Digest of the compiled version, every allowlisted asset and the worker. */
export function pwaVersion(
  appCodeVersion: string,
  assetDigests: ReadonlyArray<readonly [string, string]>,
  workerTemplate: string,
): string {
  const rows = assetDigests
    .map(([path, digest]) => `${path}\0${digest}\n`)
    .join("");
  return sha256Hex(`${appCodeVersion}\0${rows}\0${workerTemplate}`);
}

/** Expand the worker template; replacers are functions so `$` stays literal. */
export function expandWorker(
  template: string,
  version: string,
  appCodeVersion: string,
  assets: readonly string[],
): string {
  return template
    .replaceAll("__APP_VERSION__", () => version)
    .replaceAll("__APP_CODE_VERSION__", () => appCodeVersion)
    .replaceAll("__APP_ASSETS__", () => JSON.stringify(assets));
}

export interface AssetManifest {
  version: string;
  appCodeVersion: string;
  assets: string[];
}

export function readAssetManifest(webRoot: string): AssetManifest {
  const manifest = readJson(
    join(webRoot, "pwa-assets.json"),
    "generated PWA asset manifest is unavailable",
  );
  const version = isRecord(manifest) ? manifest.version : undefined;
  const assets = isRecord(manifest) ? manifest.assets : undefined;
  if (
    typeof version !== "string" ||
    !/^[a-f0-9]{64}$/.test(version) ||
    !Array.isArray(assets) ||
    assets.length === 0 ||
    assets.length > 512 ||
    assets.some(
      (path) =>
        typeof path !== "string" ||
        !path.startsWith("/app/") ||
        path.includes("?") ||
        path.includes("#") ||
        path.includes(".."),
    )
  ) {
    throw new AcceptanceFailure("generated PWA asset manifest is invalid");
  }
  const assetPaths = assets as string[];
  if (new Set(assetPaths).size !== assetPaths.length) {
    throw new AcceptanceFailure("generated PWA asset manifest has duplicates");
  }
  const workerTemplate = productionWorkerTemplate();
  const appCodeVersion = readAppCodeVersion(webRoot);
  const digests: Array<readonly [string, string]> = [];
  for (const path of assetPaths) {
    let content: Buffer;
    try {
      content = readFileSync(join(webRoot, removePrefix(path, "/app/")));
    } catch {
      throw new AcceptanceFailure("generated PWA asset is missing");
    }
    digests.push([path, sha256Hex(content)]);
  }
  if (pwaVersion(appCodeVersion, digests, workerTemplate) !== version) {
    throw new AcceptanceFailure(
      "generated PWA version does not match its assets",
    );
  }
  const expectedWorker = expandWorker(
    workerTemplate,
    version,
    appCodeVersion,
    assetPaths,
  );
  let actualWorker: string;
  try {
    actualWorker = readFileSync(join(webRoot, "app-sw.js"), "utf8");
  } catch {
    throw new AcceptanceFailure("generated app worker is unavailable");
  }
  if (actualWorker.includes("__APP_") || actualWorker !== expectedWorker) {
    throw new AcceptanceFailure(
      "generated app worker differs from the build output",
    );
  }
  return { version, appCodeVersion, assets: assetPaths };
}

function chmodTree(root: string): void {
  chmodSync(root, 0o700);
  for (const entry of readdirSync(root, {
    recursive: true,
    withFileTypes: true,
  })) {
    if (entry.isSymbolicLink()) continue;
    chmodSync(
      join(entry.parentPath, entry.name),
      entry.isDirectory() ? 0o700 : 0o600,
    );
  }
}

/**
 * Copy a web root and rewrite its worker and version so the copy looks like a
 * new release. Returns the new worker version.
 */
export function writeNextWebRoot(
  webRoot: string,
  target: string,
  versionMarker = "disposable browser update fixture",
): string {
  cpSync(webRoot, target, { recursive: true, verbatimSymlinks: true });
  chmodTree(target);
  const manifestPath = join(target, "pwa-assets.json");
  const manifest = readJson(
    manifestPath,
    "generated PWA asset manifest is unavailable",
  );
  const assets = isRecord(manifest) ? manifest.assets : undefined;
  if (!Array.isArray(assets) || assets.length === 0) {
    throw new AcceptanceFailure("generated PWA asset manifest is invalid");
  }
  const sortedAssets = [...(assets as string[])].sort();
  const appCodeVersion = readAppCodeVersion(target);

  appendFileSync(join(target, "offline.css"), `\n/* ${versionMarker} */\n`, {
    encoding: "utf8",
  });

  const template = productionWorkerTemplate();
  const digests: Array<readonly [string, string]> = [];
  for (const path of sortedAssets) {
    let content: Buffer;
    try {
      content = readFileSync(join(target, removePrefix(path, "/app/")));
    } catch {
      throw new AcceptanceFailure("a generated app cache asset is missing");
    }
    digests.push([path, sha256Hex(content)]);
  }
  const version = pwaVersion(appCodeVersion, digests, template);
  (manifest as Record<string, unknown>).version = version;
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  const worker = expandWorker(template, version, appCodeVersion, sortedAssets);
  if (worker.includes("__APP_")) {
    throw new AcceptanceFailure(
      "generated app worker has an unexpanded marker",
    );
  }
  writeFileSync(join(target, "app-sw.js"), worker, "utf8");
  return version;
}

function whichExecutable(name: string): string | null {
  for (const dir of (process.env.PATH ?? "").split(delimiter)) {
    if (!dir) continue;
    const candidate = join(dir, name);
    try {
      accessSync(candidate, constants.X_OK);
      if (statSync(candidate).isFile()) return candidate;
    } catch {
      // keep looking
    }
  }
  return null;
}

const CANDIDATE_BUILD_TIMEOUT_MS = 240_000;

function runNpmBuild(
  npm: string,
  cwd: string,
  env: NodeJS.ProcessEnv,
): Promise<number | null> {
  return new Promise((resolve, reject) => {
    const child = spawn(npm, ["run", "build"], {
      cwd,
      env,
      stdio: "ignore",
      detached: true,
    });
    const killGroup = () => {
      try {
        if (child.pid !== undefined) process.kill(-child.pid, "SIGKILL");
      } catch {
        // already gone
      }
    };
    const timer = setTimeout(() => {
      killGroup();
      reject(
        new AcceptanceFailure("candidate SvelteKit build did not complete"),
      );
    }, CANDIDATE_BUILD_TIMEOUT_MS);
    child.on("error", () => {
      clearTimeout(timer);
      reject(
        new AcceptanceFailure("candidate SvelteKit build did not complete"),
      );
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
}

/**
 * Build a second SvelteKit app with a distinct compiled version (`npm run
 * build` in a copy of `web/`, sharing the installed node_modules).
 */
export async function buildCandidateWebRoot(
  privateDir: string,
  appCodeVersion: string,
): Promise<string> {
  const sourceWeb = join(REPO_ROOT, "web");
  const dependencies = join(sourceWeb, "node_modules");
  const npm = whichExecutable("npm");
  if (
    !existsSync(dependencies) ||
    !statSync(dependencies).isDirectory() ||
    npm === null
  ) {
    throw new AcceptanceFailure(
      "candidate SvelteKit build tools are unavailable",
    );
  }

  const candidateSource = join(privateDir, "candidate-app-source");
  const ignored = new Set([
    ".git",
    "node_modules",
    "build",
    ".svelte-kit",
    ".env",
  ]);
  cpSync(sourceWeb, candidateSource, {
    recursive: true,
    dereference: true,
    filter: (source) => {
      const name = basename(source);
      return !ignored.has(name) && !name.startsWith(".env.");
    },
  });
  symlinkSync(
    realpathSync(dependencies),
    join(candidateSource, "node_modules"),
    "dir",
  );
  const status = await runNpmBuild(npm, candidateSource, {
    ...process.env,
    STRICKNANI_PWA_TEST_VERSION: appCodeVersion,
  });
  if (status !== 0) {
    throw new AcceptanceFailure(
      `candidate SvelteKit build failed with status ${status}`,
    );
  }

  const candidateRoot = join(candidateSource, "build");
  const candidate = readAssetManifest(candidateRoot);
  if (candidate.appCodeVersion !== appCodeVersion) {
    throw new AcceptanceFailure(
      "candidate build used the wrong compiled app version",
    );
  }
  if (
    !existsSync(candidateRoot) ||
    !statSync(candidateRoot).isDirectory() ||
    !existsSync(join(candidateRoot, "200.html"))
  ) {
    throw new AcceptanceFailure(
      "candidate SvelteKit build output is incomplete",
    );
  }
  return candidateRoot;
}
