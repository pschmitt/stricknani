/* App-scope public shell cache only. Private pages, APIs, media, and drafts stay network-only. */
const APP_PREFIX = "/app/";
const CACHE_PREFIX = "stricknani-app-shell-";
// Written by the former cross-tab update protocol; removed on activation.
const LEGACY_UPDATE_STATE_CACHE = "stricknani-app-update-state-v1";
const APP_VERSION = "__APP_VERSION__";
const APP_CODE_VERSION = "__APP_CODE_VERSION__";
const CACHE_NAME = `${CACHE_PREFIX}${APP_VERSION}`;
const PUBLIC_ASSETS = __APP_ASSETS__;
const IMMUTABLE_ASSET_PREFIX = `${APP_PREFIX}_app/immutable/`;
const REQUIRED_OFFLINE_ASSETS = [
  `${APP_PREFIX}manifest.webmanifest`,
  `${APP_PREFIX}offline.html`,
  `${APP_PREFIX}offline.css`,
  `${APP_PREFIX}offline-theme.js`,
  `${APP_PREFIX}favicon.svg`,
  `${APP_PREFIX}icons/icon-192.png`,
  `${APP_PREFIX}icons/icon-512.png`,
  `${APP_PREFIX}icons/icon-maskable-512.png`,
];
const MAX_ASSETS = 512;
const MAX_ASSET_BYTES = 5 * 1024 * 1024;
const MAX_TOTAL_BYTES = 25 * 1024 * 1024;
const OFFLINE_PATH = `${APP_PREFIX}offline.html`;
// Caches of this many app versions (the current one included) are kept, so a
// tab still running one of the previous versions can lazy-load its hashed
// chunks. A tab further behind than that falls back to the network; if a chunk
// is gone there too, SvelteKit reloads the tab into the current version.
const MAX_RETAINED_CACHES = 3;
let cacheWriteQueue = Promise.resolve();

// No message makes this worker wait for or ask other tabs: frozen background
// tabs never answer, which used to block every update.
self.addEventListener("message", (event) => {
  if (event.data?.type === "GET_VERSION") {
    event.ports[0]?.postMessage({
      version: APP_VERSION,
      workerVersion: APP_VERSION,
      appVersion: APP_CODE_VERSION,
    });
  } else if (event.data?.type === "PRUNE_APP_CACHES") {
    event.waitUntil(
      pruneOldCaches().then((pruned) =>
        event.ports[0]?.postMessage({
          type: "PRUNE_REQUEST_ACCEPTED",
          pruned,
        }),
      ),
    );
  }
});

// Web Push: the server sends timer reminders as {title, body, tag, path} plus
// the optional {image, open_label, timestamp}. Every push must show a
// notification (userVisibleOnly); the tag replaces an earlier one for the same
// timer (renotify makes it alert again), and the click handler below opens
// `path`.
const MAX_THUMBNAIL_BYTES = 1024 * 1024;
const THUMBNAIL_TIMEOUT_MS = 4000;

// Project thumbnails need the session cookie, and the browser may fetch a
// notification's image URL without it. This worker is same-origin, so it
// fetches the thumbnail itself and hands the browser a data: URL. The path
// comes from our own server's (encrypted) push payload; only same-origin image
// responses up to MAX_THUMBNAIL_BYTES are used, and nothing is cached. Any
// failure just means a notification without a picture.
async function thumbnailDataUrl(path) {
  if (
    typeof path !== "string" ||
    !path.startsWith("/") ||
    path.startsWith("//")
  ) {
    return undefined;
  }
  try {
    const url = new URL(path, self.location.origin);
    if (url.origin !== self.location.origin) {
      return undefined;
    }
    const response = await fetch(url.pathname, {
      credentials: "same-origin",
      signal: AbortSignal.timeout(THUMBNAIL_TIMEOUT_MS),
    });
    const type = response.headers.get("content-type") ?? "";
    if (!response.ok || !type.startsWith("image/")) return undefined;
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length === 0 || bytes.length > MAX_THUMBNAIL_BYTES) {
      return undefined;
    }
    let binary = "";
    for (let offset = 0; offset < bytes.length; offset += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
    }
    return `data:${type.split(";")[0]};base64,${btoa(binary)}`;
  } catch {
    return undefined;
  }
}

self.addEventListener("push", (event) => {
  let message = {};
  try {
    message = event.data?.json() ?? {};
  } catch {
    message = {};
  }
  const title =
    typeof message.title === "string" && message.title
      ? message.title
      : "Stricknani";
  event.waitUntil(
    (async () => {
      const picture = await thumbnailDataUrl(message.image);
      const tag = typeof message.tag === "string" ? message.tag : undefined;
      const options = {
        body: typeof message.body === "string" ? message.body : "",
        tag,
        renotify: tag !== undefined,
        icon: picture ?? `${APP_PREFIX}icons/icon-192.png`,
        data: {
          path: typeof message.path === "string" ? message.path : APP_PREFIX,
        },
      };
      if (picture) options.image = picture;
      if (Number.isFinite(message.timestamp)) {
        options.timestamp = message.timestamp;
      }
      if (typeof message.open_label === "string" && message.open_label) {
        options.actions = [{ action: "open", title: message.open_label }];
      }
      await self.registration.showNotification(title, options);
    })(),
  );
});

// A notification opens what it is about. An open app tab is focused and
// navigates in-app (its unsaved-changes guard still applies); without one, a
// new window opens on that path. Only same-origin app paths are accepted.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const raw = event.notification.data?.path;
  let target = new URL(APP_PREFIX, self.location.origin);
  if (typeof raw === "string" && raw.startsWith(APP_PREFIX)) {
    const candidate = new URL(raw, self.location.origin);
    if (
      candidate.origin === self.location.origin &&
      candidate.pathname.startsWith(APP_PREFIX)
    ) {
      target = candidate;
    }
  }
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
      });
      const tab =
        windows.find((client) => client.focused) ??
        windows.find((client) =>
          new URL(client.url).pathname.startsWith(APP_PREFIX),
        );
      if (tab) {
        await tab.focus();
        tab.postMessage({
          type: "OPEN_PATH",
          path: `${target.pathname}${target.search}`,
        });
        return;
      }
      await self.clients.openWindow(target.href);
    })(),
  );
});

// CacheStorage lists caches in creation order, and install recreates this
// version's cache, so the last names are the newest versions.
async function pruneOldCaches() {
  await cacheWriteQueue;
  await caches.delete(LEGACY_UPDATE_STATE_CACHE);
  const older = (await caches.keys()).filter(
    (key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME,
  );
  const stale = older.slice(
    0,
    Math.max(0, older.length - (MAX_RETAINED_CACHES - 1)),
  );
  for (const key of stale) await caches.delete(key);
  return stale.length > 0;
}

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

function isPromotableImmutableAsset(url) {
  return (
    url.origin === self.location.origin &&
    url.search === "" &&
    url.hash === "" &&
    isImmutableAssetPath(url.pathname) &&
    PUBLIC_ASSETS.includes(url.pathname)
  );
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
      // Take over right away. Route HTML is network-only and app assets are
      // content-hashed, so open tabs keep working on the code they loaded;
      // each tab moves to the new version on its next full load.
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      await self.clients.claim();
      await pruneOldCaches();
    })(),
  );
});

async function retainedImmutableResponse(request) {
  const names = (await caches.keys())
    .filter((name) => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME)
    .sort()
    .reverse();
  for (const name of names) {
    const response = await (
      await caches.open(name)
    ).match(request, {
      ignoreSearch: false,
    });
    if (response?.ok) return response;
  }
  return null;
}

function publicAsset(request, url) {
  let finishBackground;
  const background = new Promise((resolve) => {
    finishBackground = resolve;
  });
  const response = (async () => {
    let cache = null;
    try {
      cache = await caches.open(CACHE_NAME);
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
      if (responseMatches(networkResponse, url)) {
        if (
          PUBLIC_ASSETS.includes(url.pathname) &&
          isImmutableAssetPath(url.pathname)
        ) {
          void queueImmutableCacheWrite(cache, url, networkResponse.clone())
            .catch(() => undefined)
            .finally(finishBackground);
        } else finishBackground();
        return networkResponse;
      }
      const retained = isImmutableAssetPath(url.pathname)
        ? await retainedImmutableResponse(request)
        : null;
      if (
        retained &&
        cache &&
        isPromotableImmutableAsset(url) &&
        responseMatches(retained, url)
      ) {
        void queueImmutableCacheWrite(cache, url, retained.clone())
          .catch(() => undefined)
          .finally(finishBackground);
        return retained;
      }
      finishBackground();
      return retained || networkResponse;
    } catch (error) {
      if (isImmutableAssetPath(url.pathname)) {
        const retained = await retainedImmutableResponse(request).catch(
          () => null,
        );
        if (retained) {
          if (
            cache &&
            isPromotableImmutableAsset(url) &&
            responseMatches(retained, url)
          ) {
            void queueImmutableCacheWrite(cache, url, retained.clone())
              .catch(() => undefined)
              .finally(finishBackground);
            return retained;
          }
          finishBackground();
          return retained;
        }
      }
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
    (PUBLIC_ASSETS.includes(url.pathname) || isImmutableAssetPath(url.pathname))
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
