import { createHash } from "node:crypto";
import { readdir, readFile, unlink, writeFile } from "node:fs/promises";
import { extname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { MASCOT_CSS, mascotSvg, renderErrorPages } from "./error-pages.mjs";
import { svelteKitAnnouncerStyle } from "./sveltekit-announcer-style.mjs";

const buildRoot = fileURLToPath(new URL("../build/", import.meta.url));
await renderOfflinePage(buildRoot);
// The playful 404 and 50x pages carry inline style and script, so they must exist
// before the hash scan below.
await renderErrorPages(buildRoot);
const htmlFiles = await listHtmlFiles(buildRoot);
const scriptHashes = new Set();
const styleHashes = new Set();

for (const file of htmlFiles) {
  const html = await readFile(file, "utf8");
  if (/<[a-z][^>]*\sstyle\s*=/i.test(html)) {
    throw new Error(
      `Inline style attributes are incompatible with strict style-src CSP: ${file}`,
    );
  }
  for (const [, attributes, body] of html.matchAll(
    /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi,
  )) {
    if (!/\bsrc\s*=/i.test(attributes)) scriptHashes.add(hash(body));
  }
  for (const [, , body] of html.matchAll(
    /<style\b([^>]*)>([\s\S]*?)<\/style\s*>/gi,
  )) {
    styleHashes.add(hash(body));
  }
}

const manifest = {
  script_hashes: [...scriptHashes].sort(),
  style_hashes: [...styleHashes].sort(),
  style_attribute_hashes: [(await svelteKitAnnouncerStyle()).hash],
};

await writeFile(
  new URL("../build/csp.json", import.meta.url),
  `${JSON.stringify(manifest, null, 2)}\n`,
);

// The app worker receives a generated, exact public-file allowlist. It is an
// internal build artifact, not a route; in particular the translation catalog
// and all route HTML stay out of CacheStorage.
const pwaAssets = await collectPwaAssets(buildRoot);
const appVersionJson = JSON.parse(
  await readFile(join(buildRoot, "_app/version.json"), "utf8"),
);
const appCodeVersion = appVersionJson?.version;
if (
  typeof appCodeVersion !== "string" ||
  appCodeVersion.length === 0 ||
  appCodeVersion.length > 128
) {
  throw new Error("SvelteKit compiled app version is missing or invalid");
}
const workerTemplate = await readFile(
  new URL("../static/service-worker.js", import.meta.url),
  "utf8",
);
const assetDigestInput = await Promise.all(
  pwaAssets.map(async (path) => {
    const diskPath = join(buildRoot, path.slice("/app/".length));
    const content = await readFile(diskPath);
    return `${path}\0${createHash("sha256").update(content).digest("hex")}\n`;
  }),
);
const version = createHash("sha256")
  .update(`${appCodeVersion}\0${assetDigestInput.join("")}\0${workerTemplate}`)
  .digest("hex");
const pwaManifest = { version, assets: pwaAssets };
await writeFile(
  new URL("../build/pwa-assets.json", import.meta.url),
  `${JSON.stringify(pwaManifest, null, 2)}\n`,
);
const worker = workerTemplate
  .replaceAll("__APP_VERSION__", version)
  .replaceAll("__APP_CODE_VERSION__", appCodeVersion)
  .replace("__APP_ASSETS__", JSON.stringify(pwaAssets));
if (worker.includes("__APP_")) {
  throw new Error("App service worker has an unexpanded build marker");
}
await writeFile(new URL("../build/app-sw.js", import.meta.url), worker);
// Vite copies static files verbatim. Keep only the generated worker available
// through the explicit Rust `/app/sw.js` route, never the unexpanded template.
await unlink(new URL("../build/service-worker.js", import.meta.url)).catch(
  (error) => {
    if (error.code !== "ENOENT") throw error;
  },
);

/** @param {string} directory @returns {Promise<string[]>} */
async function listHtmlFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await listHtmlFiles(path)));
    else if (entry.isFile() && extname(entry.name) === ".html")
      files.push(path);
  }
  return files;
}

/** @param {string} directory */
async function renderOfflinePage(directory) {
  const catalog = JSON.parse(
    await readFile(join(directory, "messages.json"), "utf8"),
  );
  const msgids = {
    STATUS: "No connection",
    TITLE: "Winky lost the thread",
    DESCRIPTION:
      "We couldn’t reach Stricknani. Check your connection, because Wi-Fi loves hiding in the yarn basket. Your projects, yarn, photos, and edits are not stored offline.",
    RETRY: "Try again",
  };
  let html = await readFile(join(directory, "offline.html"), "utf8");
  html = html.replace("<!-- __MASCOT__ -->", mascotSvg("sad"));
  const css = await readFile(join(directory, "offline.css"), "utf8");
  await writeFile(
    join(directory, "offline.css"),
    css.replace("/* __MASCOT_CSS__ */", MASCOT_CSS),
  );
  for (const [name, msgid] of Object.entries(msgids)) {
    for (const locale of ["en", "de"]) {
      const translation = catalog?.[locale]?.[msgid];
      if (typeof translation !== "string" || translation.trim() === "") {
        throw new Error(`Missing offline ${locale} translation for ${msgid}`);
      }
      html = html.replaceAll(
        `__PWA_MSG_${name}_${locale.toUpperCase()}__`,
        escapeHtml(translation),
      );
    }
  }
  if (/__PWA_MSG_[A-Z_]+__/.test(html)) {
    throw new Error("Offline page has an unresolved catalog message");
  }
  await writeFile(join(directory, "offline.html"), html);
}

/** @param {string} value */
function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (character) => {
    /** @type {Record<string, string>} */
    const entities = {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    };
    return entities[character] ?? character;
  });
}

/** @param {string} directory @returns {Promise<string[]>} */
async function collectPwaAssets(directory) {
  const immutableRoot = join(directory, "_app/immutable");
  const files = [];
  for (const path of await listFiles(immutableRoot)) {
    if ([".js", ".css"].includes(extname(path))) {
      files.push(`/app/${relative(directory, path).replaceAll("\\", "/")}`);
    }
  }
  // The worker refuses to install unless every REQUIRED_OFFLINE_ASSETS entry is
  // in this list, so read them from the worker itself instead of repeating them.
  files.push(...(await requiredOfflineAssets()));
  const assets = [...new Set(files)].sort();
  if (assets.length > 512) {
    throw new Error(`App offline asset count exceeds 512: ${assets.length}`);
  }
  let totalBytes = 0;
  for (const asset of assets) {
    if (
      !asset.startsWith("/app/") ||
      asset.includes("?") ||
      asset.includes("#") ||
      asset.includes("..")
    ) {
      throw new Error(`Invalid app offline asset path: ${asset}`);
    }
    const diskPath = join(directory, asset.slice("/app/".length));
    const content = await readFile(diskPath);
    if (content.length > 5 * 1024 * 1024) {
      throw new Error(`App offline asset exceeds 5 MiB: ${asset}`);
    }
    totalBytes += content.length;
  }
  if (totalBytes > 25 * 1024 * 1024) {
    throw new Error(`App offline assets exceed 25 MiB: ${totalBytes}`);
  }
  return assets;
}

/** @param {string} directory @returns {Promise<string[]>} */
async function listFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await listFiles(path)));
    else if (entry.isFile()) files.push(path);
  }
  return files;
}

/** @param {string} content @returns {string} */
function hash(content) {
  return `sha256-${createHash("sha256").update(content, "utf8").digest("base64")}`;
}

async function requiredOfflineAssets() {
  const worker = await readFile(
    new URL("../static/service-worker.js", import.meta.url),
    "utf8",
  );
  const block = worker.match(/const REQUIRED_OFFLINE_ASSETS = \[([\s\S]*?)\];/);
  if (!block) {
    throw new Error("service-worker.js has no REQUIRED_OFFLINE_ASSETS list");
  }
  const paths = [...block[1].matchAll(/`\$\{APP_PREFIX\}([^`]+)`/g)].map(
    (match) => `/app/${match[1]}`,
  );
  if (paths.length === 0) {
    throw new Error("service-worker.js REQUIRED_OFFLINE_ASSETS is empty");
  }
  return paths;
}
