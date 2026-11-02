/* App-scope public shell cache only. Private pages, APIs, media, and drafts stay network-only. */
const APP_PREFIX = "/app/";
const CACHE_PREFIX = "stricknani-app-shell-";
const UPDATE_STATE_CACHE = "stricknani-app-update-state-v1";
const UPDATE_STATE_URL = new URL(
  "/__stricknani_app_update_activation_v1__",
  self.location.origin,
).href;
const APP_VERSION = "__APP_VERSION__";
const APP_CODE_VERSION = "__APP_CODE_VERSION__";
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
const MAX_APP_CLIENTS = 24;
const OFFLINE_PATH = `${APP_PREFIX}offline.html`;
const UPDATE_MESSAGE_TIMEOUT_MS = 5_000;
let cacheWriteQueue = Promise.resolve();
let preparedUpdate = null;

self.addEventListener("message", (event) => {
  if (event.data?.type === "GET_VERSION") {
    event.ports[0]?.postMessage({
      version: APP_VERSION,
      workerVersion: APP_VERSION,
      appVersion: APP_CODE_VERSION,
    });
    return;
  }

  const source = event.source;
  if (!isAppWindowClient(source)) {
    event.ports[0]?.postMessage({ type: "UPDATE_REJECTED", reason: "client" });
    return;
  }

  if (event.data?.type === "PREPARE_UPDATE") {
    event.waitUntil(prepareUpdate(event, source));
  } else if (event.data?.type === "COMMIT_UPDATE") {
    event.waitUntil(commitUpdate(event, source));
  } else if (event.data?.type === "CANCEL_UPDATE") {
    event.waitUntil(cancelUpdate(event, source));
  } else if (event.data?.type === "RELOAD_ACK") {
    event.waitUntil(acknowledgeReload(event, source));
  } else if (event.data?.type === "PRUNE_APP_CACHES") {
    event.waitUntil(
      pruneOldCachesIfSafe().then((pruned) =>
        event.ports[0]?.postMessage({
          type: "PRUNE_REQUEST_ACCEPTED",
          pruned,
        }),
      ),
    );
  }
});

function isAppWindowClient(source) {
  if (
    !source ||
    typeof source.id !== "string" ||
    typeof source.url !== "string"
  )
    return false;
  try {
    const url = new URL(source.url);
    return url.origin === self.location.origin && isAppClientPath(url.pathname);
  } catch {
    return false;
  }
}

function updateRequestMatches(data) {
  return (
    typeof data?.updateId === "string" &&
    /^[a-f0-9-]{32,36}$/.test(data.updateId) &&
    data.workerVersion === APP_VERSION &&
    data.appVersion === APP_CODE_VERSION
  );
}

function validActivationState(value) {
  return (
    value &&
    typeof value.updateId === "string" &&
    /^[a-f0-9-]{32,36}$/.test(value.updateId) &&
    value.appVersion === APP_CODE_VERSION &&
    value.workerVersion === APP_VERSION &&
    Array.isArray(value.clientIds) &&
    value.clientIds.length > 0 &&
    value.clientIds.length <= MAX_APP_CLIENTS &&
    value.clientIds.every(
      (id) => typeof id === "string" && id.length > 0 && id.length <= 128,
    ) &&
    new Set(value.clientIds).size === value.clientIds.length
  );
}

async function writeActivationState(transaction) {
  const value = {
    updateId: transaction.updateId,
    appVersion: transaction.appVersion,
    workerVersion: transaction.workerVersion,
    clientIds: transaction.clientIds,
  };
  if (!validActivationState(value)) return false;
  const serialized = JSON.stringify(value);
  if (serialized.length > 4096) return false;
  const cache = await caches.open(UPDATE_STATE_CACHE);
  await cache.put(
    UPDATE_STATE_URL,
    new Response(serialized, {
      headers: { "content-type": "application/json; charset=utf-8" },
    }),
  );
  return true;
}

async function readActivationState() {
  const response = await caches.match(UPDATE_STATE_URL, {
    cacheName: UPDATE_STATE_CACHE,
    ignoreSearch: false,
  });
  if (!response) return null;
  try {
    const value = await response.json();
    return validActivationState(value) ? value : null;
  } catch {
    return null;
  }
}

async function deleteActivationState(updateId) {
  if (!(await caches.has(UPDATE_STATE_CACHE))) return false;
  const response = await caches.match(UPDATE_STATE_URL, {
    cacheName: UPDATE_STATE_CACHE,
    ignoreSearch: false,
  });
  if (!response) {
    await deleteEmptyActivationStateCache();
    return false;
  }
  let state;
  try {
    const value = await response.json();
    state = validActivationState(value) ? value : null;
  } catch {
    return false;
  }
  if (!state || typeof updateId !== "string" || state.updateId !== updateId)
    return false;
  const cache = await caches.open(UPDATE_STATE_CACHE);
  await cache.delete(UPDATE_STATE_URL);
  if ((await cache.keys()).length === 0)
    await caches.delete(UPDATE_STATE_CACHE);
  return true;
}

async function deleteEmptyActivationStateCache() {
  if (!(await caches.has(UPDATE_STATE_CACHE))) return;
  const cache = await caches.open(UPDATE_STATE_CACHE);
  if ((await cache.keys()).length === 0)
    await caches.delete(UPDATE_STATE_CACHE);
}

async function appWindowClients() {
  const clients = await self.clients.matchAll({
    type: "window",
    includeUncontrolled: true,
  });
  return clients.filter(isAppWindowClient);
}

function sameClientSet(clients, expectedIds) {
  const actualIds = clients.map((client) => client.id).sort();
  const expected = [...expectedIds].sort();
  return (
    actualIds.length === expected.length &&
    actualIds.every((id, index) => id === expected[index])
  );
}

function askClient(
  client,
  type,
  updateId,
  appVersion,
  workerVersion,
  sourcePageVersion,
) {
  return new Promise((resolve) => {
    const channel = new MessageChannel();
    const timeout = setTimeout(() => {
      channel.port1.close();
      resolve(null);
    }, UPDATE_MESSAGE_TIMEOUT_MS);
    channel.port1.onmessage = (event) => {
      clearTimeout(timeout);
      channel.port1.close();
      resolve(event.data);
    };
    try {
      client.postMessage(
        { type, updateId, appVersion, workerVersion, sourcePageVersion },
        [channel.port2],
      );
    } catch {
      clearTimeout(timeout);
      channel.port1.close();
      resolve(null);
    }
  });
}

async function cancelPreparedClients(transaction, reason) {
  if (!transaction) return;
  transaction.cancelled = true;
  const clients = await appWindowClients();
  const preparedClients = clients.filter((client) =>
    transaction.clientIds.includes(client.id),
  );
  await Promise.all(
    preparedClients.map((client) =>
      askClient(
        client,
        "CANCEL_UPDATE",
        transaction.updateId,
        transaction.appVersion,
        transaction.workerVersion,
      ),
    ),
  );
  for (const client of preparedClients) {
    client.postMessage({
      type: "CANCEL_UPDATE",
      updateId: transaction.updateId,
    });
  }
  transaction.reason = reason;
}

async function failUpdate(event, transaction, reason) {
  await cancelPreparedClients(transaction, reason);
  if (transaction) await deleteActivationState(transaction.updateId);
  if (preparedUpdate === transaction) preparedUpdate = null;
  event.ports[0]?.postMessage({ type: "UPDATE_BLOCKED", reason });
}

async function prepareUpdate(event, source) {
  const data = event.data;
  if (!updateRequestMatches(data)) {
    event.ports[0]?.postMessage({ type: "UPDATE_BLOCKED", reason: "version" });
    return;
  }
  if (
    typeof data.sourcePageVersion !== "string" ||
    data.sourcePageVersion.length === 0 ||
    data.sourcePageVersion.length > 128
  ) {
    event.ports[0]?.postMessage({ type: "UPDATE_BLOCKED", reason: "version" });
    return;
  }
  if (preparedUpdate) {
    event.ports[0]?.postMessage({ type: "UPDATE_BLOCKED", reason: "busy" });
    return;
  }

  const transaction = {
    updateId: data.updateId,
    appVersion: data.appVersion,
    workerVersion: data.workerVersion,
    sourcePageVersion: data.sourcePageVersion,
    clientIds: [],
    cancelled: false,
    committing: false,
  };
  preparedUpdate = transaction;
  const clients = await appWindowClients();
  if (
    transaction.cancelled ||
    clients.length === 0 ||
    clients.length > MAX_APP_CLIENTS ||
    !clients.some((client) => client.id === source.id)
  ) {
    await failUpdate(event, transaction, "changed");
    return;
  }
  transaction.clientIds = clients.map((client) => client.id);

  const prepared = await Promise.all(
    clients.map((client) =>
      askClient(
        client,
        "PREPARE_UPDATE",
        transaction.updateId,
        transaction.appVersion,
        transaction.workerVersion,
        data.sourcePageVersion,
      ),
    ),
  );
  if (transaction.cancelled) {
    await failUpdate(event, transaction, "cancelled");
    return;
  }
  const blocked = prepared.find(
    (result) =>
      result?.type !== "UPDATE_PREPARED" ||
      result.pageVersion !== data.sourcePageVersion,
  );
  if (blocked) {
    const reason =
      blocked && typeof blocked.reason === "string"
        ? blocked.reason
        : "unresponsive";
    await failUpdate(event, transaction, reason);
    return;
  }

  const currentClients = await appWindowClients();
  if (
    transaction.cancelled ||
    !sameClientSet(currentClients, transaction.clientIds)
  ) {
    await failUpdate(event, transaction, "changed");
    return;
  }
  event.ports[0]?.postMessage({
    type: "UPDATE_PREPARED",
    updateId: data.updateId,
  });
}

async function commitUpdate(event, source) {
  const data = event.data;
  const transaction = preparedUpdate;
  if (
    !transaction ||
    transaction.cancelled ||
    transaction.committing ||
    transaction.updateId !== data.updateId ||
    !updateRequestMatches(data) ||
    !transaction.clientIds.includes(source.id)
  ) {
    event.ports[0]?.postMessage({ type: "UPDATE_BLOCKED", reason: "expired" });
    return;
  }

  let clients = await appWindowClients();
  if (!sameClientSet(clients, transaction.clientIds)) {
    await failUpdate(event, transaction, "changed");
    return;
  }
  const validated = await Promise.all(
    clients.map((client) =>
      askClient(
        client,
        "VALIDATE_UPDATE",
        transaction.updateId,
        transaction.appVersion,
        transaction.workerVersion,
      ),
    ),
  );
  if (
    transaction.cancelled ||
    validated.some(
      (result) =>
        result?.type !== "UPDATE_VALIDATED" ||
        result.pageVersion !== transaction.sourcePageVersion,
    )
  ) {
    await failUpdate(event, transaction, "changed");
    return;
  }

  clients = await appWindowClients();
  if (transaction.cancelled || !sameClientSet(clients, transaction.clientIds)) {
    await failUpdate(event, transaction, "changed");
    return;
  }

  transaction.committing = true;
  try {
    if (!(await writeActivationState(transaction))) {
      transaction.committing = false;
      await failUpdate(event, transaction, "unavailable");
      return;
    }
  } catch {
    transaction.committing = false;
    await failUpdate(event, transaction, "unavailable");
    return;
  }
  clients = await appWindowClients();
  if (transaction.cancelled || !sameClientSet(clients, transaction.clientIds)) {
    transaction.committing = false;
    await failUpdate(event, transaction, "changed");
    return;
  }
  for (const client of clients) {
    client.postMessage({
      type: "UPDATE_COMMITTING",
      updateId: transaction.updateId,
      appVersion: transaction.appVersion,
      workerVersion: transaction.workerVersion,
    });
  }
  event.ports[0]?.postMessage({ type: "UPDATE_COMMIT_ACCEPTED" });
  event.waitUntil(self.skipWaiting());
}

async function cancelUpdate(event, source) {
  const transaction = preparedUpdate;
  if (
    !transaction ||
    transaction.updateId !== event.data?.updateId ||
    !transaction.clientIds.includes(source.id)
  ) {
    event.ports[0]?.postMessage({ type: "UPDATE_CANCELLED" });
    return;
  }
  if (transaction.committing) {
    event.ports[0]?.postMessage({ type: "UPDATE_CANCEL_TOO_LATE" });
    return;
  }
  await cancelPreparedClients(transaction, "cancelled");
  await deleteActivationState(transaction.updateId);
  if (preparedUpdate === transaction) preparedUpdate = null;
  event.ports[0]?.postMessage({ type: "UPDATE_CANCELLED" });
}

async function acknowledgeReload(event, source) {
  const data = event.data;
  const activation = await readActivationState();
  if (
    !isAppWindowClient(source) ||
    !updateRequestMatches(data) ||
    data.pageVersion !== APP_CODE_VERSION ||
    !activation ||
    activation.updateId !== data.updateId ||
    activation.appVersion !== data.appVersion ||
    activation.workerVersion !== data.workerVersion
  ) {
    event.ports[0]?.postMessage({ type: "RELOAD_ACK_REJECTED" });
    return;
  }
  event.ports[0]?.postMessage({ type: "RELOAD_ACK_ACCEPTED" });
  event.waitUntil(pruneOldCachesIfSafe());
}

async function activeWorkerCanPrune(expectedActive) {
  const registration = self.registration;
  if (
    !expectedActive ||
    expectedActive.state !== "activated" ||
    registration.active !== expectedActive ||
    registration.waiting ||
    registration.installing
  ) {
    return false;
  }
  const version = await askClient(expectedActive, "GET_VERSION", "", "", "");
  return (
    version?.workerVersion === APP_VERSION &&
    version.appVersion === APP_CODE_VERSION &&
    expectedActive.state === "activated" &&
    registration.active === expectedActive &&
    !registration.waiting &&
    !registration.installing
  );
}

async function deleteOldAppCaches(expectedActive) {
  if (!(await activeWorkerCanPrune(expectedActive))) return false;
  await cacheWriteQueue;
  if (!(await activeWorkerCanPrune(expectedActive))) return false;
  const cacheKeys = (await caches.keys()).filter(
    (key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME,
  );
  if (!(await activeWorkerCanPrune(expectedActive))) return false;
  for (const key of cacheKeys) {
    if (!(await activeWorkerCanPrune(expectedActive))) return false;
    await caches.delete(key);
  }
  return true;
}

async function deleteCurrentActivationState() {
  const activation = await readActivationState();
  if (
    activation?.appVersion === APP_CODE_VERSION &&
    activation.workerVersion === APP_VERSION
  ) {
    await deleteActivationState(activation.updateId);
  } else {
    await deleteEmptyActivationStateCache();
  }
}

async function pruneOldCachesIfSafe() {
  const activeWorker = self.registration.active;
  if (!(await activeWorkerCanPrune(activeWorker))) return false;
  const clients = await appWindowClients();
  if (clients.length === 0) {
    const deleted = await deleteOldAppCaches(activeWorker);
    if (deleted) await deleteCurrentActivationState();
    return deleted;
  }
  const versions = await Promise.all(
    clients.map((client) => askClient(client, "QUERY_APP_VERSION", "", "", "")),
  );
  const allCurrent = versions.every(
    (result) =>
      result?.type === "APP_VERSION" &&
      result.pageVersion === APP_CODE_VERSION &&
      result.controllerAppVersion === APP_CODE_VERSION &&
      result.controllerVersion === APP_VERSION,
  );
  if (!allCurrent) return false;
  const deleted = await deleteOldAppCaches(activeWorker);
  if (!deleted) return false;
  await deleteCurrentActivationState();
  return true;
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
      // This worker waits until every live app client is explicitly prepared.
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const activation = await readActivationState();
      if (
        activation &&
        activation.appVersion === APP_CODE_VERSION &&
        activation.workerVersion === APP_VERSION
      ) {
        const clients = await appWindowClients();
        await Promise.all(
          clients
            .filter((client) => activation.clientIds.includes(client.id))
            .map((client) =>
              askClient(
                client,
                "UPDATE_ACTIVATED",
                activation.updateId,
                activation.appVersion,
                activation.workerVersion,
              ),
            ),
        );
      }
      // Keep old immutable app assets until each live page proves it has loaded
      // this compiled version and is controlled by this worker.
      await pruneOldCachesIfSafe();
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
        await queueImmutableCacheWrite(cache, url, retained.clone()).catch(
          () => undefined,
        );
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
            await queueImmutableCacheWrite(cache, url, retained.clone()).catch(
              () => undefined,
            );
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
