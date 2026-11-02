/* App-scope public shell cache only. Private pages, APIs, media, and drafts stay network-only. */
const APP_PREFIX = "/app/";
const CACHE_PREFIX = "stricknani-app-shell-";
const APP_VERSION = "__APP_VERSION__";
const CACHE_NAME = `${CACHE_PREFIX}${APP_VERSION}`;
const PUBLIC_ASSETS = __APP_ASSETS__;
const IMMUTABLE_ASSET_PREFIX = `${APP_PREFIX}_app/immutable/`;
const REQUIRED_OFFLINE_ASSETS = [
  `${APP_PREFIX}manifest.webmanifest`,
  `${APP_PREFIX}offline.html`,
  `${APP_PREFIX}offline.css`,
  `${APP_PREFIX}favicon.svg`,
  `${APP_PREFIX}icons/icon-192.png`,
  `${APP_PREFIX}icons/icon-512.png`,
];
const MAX_ASSETS = 512;
const MAX_ASSET_BYTES = 5 * 1024 * 1024;
const MAX_TOTAL_BYTES = 25 * 1024 * 1024;
const MAX_RETAINED_CACHES = 3;
const OFFLINE_PATH = `${APP_PREFIX}offline.html`;
let cacheWriteQueue = Promise.resolve();

self.addEventListener("message", (event) => {
  if (event.data?.type === "GET_VERSION") {
    event.ports[0]?.postMessage({ version: APP_VERSION });
  }
});

function validAssetPath(path) {
  if (typeof path !== "string" || !path.startsWith(APP_PREFIX)) return false;
  if (
    path.includes("?") ||
    path.includes("#") ||
    path.includes("\\") ||
    path.includes("%")
  ) {
    return false;
  }
  const parts = path.slice(1).split("/");
  return parts.every((part) => part !== "" && part !== "." && part !== "..");
}

function responseMatches(response, url) {
  return (
    response?.ok &&
    !response.redirected &&
    response.type === "basic" &&
    response.url === url.href
  );
}

function isImmutableAssetPath(pathname) {
  return (
    pathname.startsWith(IMMUTABLE_ASSET_PREFIX) &&
    (pathname.endsWith(".js") || pathname.endsWith(".css"))
  );
}

function isAppClientPath(pathname) {
  return pathname === "/app" || pathname.startsWith(APP_PREFIX);
}

async function appClientsExist() {
  const clients = await self.clients.matchAll({
    type: "window",
    includeUncontrolled: true,
  });
  return clients.some((client) =>
    isAppClientPath(new URL(client.url).pathname),
  );
}

async function activeCacheName() {
  const active = self.registration.active;
  if (!active) return null;
  const channel = new MessageChannel();
  try {
    const reply = new Promise((resolve, reject) => {
      const timeout = setTimeout(
        () => reject(new Error("Active worker did not reply")),
        1000,
      );
      channel.port1.onmessage = (event) => {
        clearTimeout(timeout);
        resolve(event.data?.version);
      };
    });
    active.postMessage({ type: "GET_VERSION" }, [channel.port2]);
    const version = await reply;
    return typeof version === "string" && /^[a-f0-9]{64}$/.test(version)
      ? `${CACHE_PREFIX}${version}`
      : null;
  } finally {
    channel.port1.close();
  }
}

async function cacheImmutableResponse(cache, url, response) {
  const body = await response.clone().arrayBuffer();
  if (body.byteLength > MAX_ASSET_BYTES) return;

  const existing = await cache.keys();
  const replacing = existing.some((request) => request.url === url.href);
  if (!replacing && existing.length >= MAX_ASSETS) return;

  let totalBytes = body.byteLength;
  for (const request of existing) {
    if (request.url === url.href) continue;
    const cached = await cache.match(request);
    if (!cached) continue;
    totalBytes += (await cached.arrayBuffer()).byteLength;
    if (totalBytes > MAX_TOTAL_BYTES) return;
  }
  if (totalBytes <= MAX_TOTAL_BYTES) {
    await cache.put(url.href, response.clone());
  }
}

function queueImmutableCacheWrite(cache, url, response) {
  const write = cacheWriteQueue.then(() =>
    cacheImmutableResponse(cache, url, response),
  );
  cacheWriteQueue = write.catch(() => undefined);
  return write;
}

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      if (
        !/^[a-f0-9]{64}$/.test(APP_VERSION) ||
        !Array.isArray(PUBLIC_ASSETS) ||
        PUBLIC_ASSETS.length === 0 ||
        PUBLIC_ASSETS.length > MAX_ASSETS ||
        new Set(PUBLIC_ASSETS).size !== PUBLIC_ASSETS.length ||
        !PUBLIC_ASSETS.every(validAssetPath) ||
        !REQUIRED_OFFLINE_ASSETS.every((path) =>
          PUBLIC_ASSETS.includes(path),
        ) ||
        !PUBLIC_ASSETS.every(
          (path) =>
            REQUIRED_OFFLINE_ASSETS.includes(path) ||
            isImmutableAssetPath(path),
        )
      ) {
        throw new Error("Invalid generated app asset manifest");
      }

      const cacheKeys = (await caches.keys()).filter((key) =>
        key.startsWith(CACHE_PREFIX),
      );
      if (cacheKeys.length >= MAX_RETAINED_CACHES) {
        if (await appClientsExist()) {
          throw new Error(
            "Too many retained app versions while app tabs are open",
          );
        }
        const activeName = await activeCacheName();
        if (!activeName || !cacheKeys.includes(activeName)) {
          throw new Error("Cannot identify the active app cache safely");
        }
        await Promise.all(
          cacheKeys
            .filter((key) => key !== activeName)
            .map((key) => caches.delete(key)),
        );
      }

      // This name is version-specific, so a failed installation can be removed
      // without touching the currently active worker's cache.
      const outcomes = await Promise.allSettled(
        REQUIRED_OFFLINE_ASSETS.map(async (path) => {
          const url = new URL(path, self.location.origin);
          if (
            url.origin !== self.location.origin ||
            url.pathname !== path ||
            url.search !== "" ||
            url.hash !== ""
          ) {
            throw new Error("Invalid app asset URL");
          }
          const response = await fetch(url.href, {
            cache: "no-store",
            credentials: "omit",
            redirect: "error",
          });
          if (!responseMatches(response, url)) {
            void response.body?.cancel().catch(() => undefined);
            throw new Error(`App asset request failed: ${path}`);
          }
          const bytes = await response.clone().arrayBuffer();
          if (bytes.byteLength > MAX_ASSET_BYTES) {
            throw new Error(`App asset exceeds size limit: ${path}`);
          }
          return { url, response, size: bytes.byteLength };
        }),
      );
      const failed = outcomes.find((outcome) => outcome.status === "rejected");
      if (failed?.status === "rejected") throw failed.reason;
      const assets = outcomes.map((outcome) => {
        if (outcome.status !== "fulfilled") {
          throw new Error("Could not fetch app offline assets");
        }
        return outcome.value;
      });
      const totalBytes = assets.reduce((total, asset) => total + asset.size, 0);
      if (totalBytes > MAX_TOTAL_BYTES) {
        throw new Error("App asset set exceeds size limit");
      }

      await caches.delete(CACHE_NAME);
      const cache = await caches.open(CACHE_NAME);
      try {
        for (const asset of assets) {
          await cache.put(asset.url.href, asset.response);
        }
      } catch (error) {
        await caches.delete(CACHE_NAME);
        throw error;
      }
      // Do not skip waiting: the browser activates this version after all tabs
      // controlled by the previous app worker have closed.
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const oldCaches = (await caches.keys()).filter(
        (key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME,
      );
      await Promise.all(oldCaches.map((key) => caches.delete(key)));
    })(),
  );
});

function publicAsset(request, url) {
  let finishBackground;
  const background = new Promise((resolve) => {
    finishBackground = resolve;
  });
  const response = (async () => {
    try {
      const cache = await caches.open(CACHE_NAME);
      const cached = await cache.match(request, { ignoreSearch: false });
      if (cached) {
        finishBackground();
        return cached;
      }
      const networkResponse = await fetch(url.href, {
        cache: "no-store",
        credentials: "omit",
        redirect: "error",
      });
      if (
        isImmutableAssetPath(url.pathname) &&
        responseMatches(networkResponse, url)
      ) {
        void queueImmutableCacheWrite(cache, url, networkResponse.clone())
          .catch(() => undefined)
          .finally(finishBackground);
      } else {
        finishBackground();
      }
      return networkResponse;
    } catch (error) {
      finishBackground();
      throw error;
    }
  })();
  return { response, background };
}

async function appNavigation(request) {
  try {
    return await fetch(request);
  } catch {
    const cache = await caches.open(CACHE_NAME);
    return (await cache.match(OFFLINE_PATH)) || Response.error();
  }
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  if (
    url.search === "" &&
    url.hash === "" &&
    PUBLIC_ASSETS.includes(url.pathname)
  ) {
    const asset = publicAsset(request, url);
    event.respondWith(asset.response);
    event.waitUntil(asset.background);
    return;
  }

  if (request.mode === "navigate" && url.pathname.startsWith(APP_PREFIX)) {
    // Route HTML remains network-only. The shell is only an offline error page.
    event.respondWith(appNavigation(request));
  }
});
