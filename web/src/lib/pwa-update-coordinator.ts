import { version as appVersion } from "$app/env";
import { hasPendingApiMutations } from "#lib/api.ts";

export type UpdateReadiness = { dirty: boolean; busy: boolean };

type UpdateToken = {
  updateId: string;
  appVersion: string;
  workerVersion: string;
};

type UpdateGuard = () => UpdateReadiness;
type WorkerMessage = {
  type?: unknown;
  updateId?: unknown;
  appVersion?: unknown;
  workerVersion?: unknown;
  reason?: unknown;
};

const TOKEN_KEY = "stricknani.pwa-update";
const PREPARE_LOCK_TIMEOUT_MS = 30_000;
const COMMIT_LOCK_TIMEOUT_MS = 45_000;
const guards = new Set<UpdateGuard>();
let lockedToken: UpdateToken | null = null;
let inertSnapshot: Array<{ element: HTMLElement; wasInert: boolean }> = [];
let channel: BroadcastChannel | null = null;
let reloadTimer: number | null = null;
let prepareTimer: number | null = null;
let commitTimer: number | null = null;

export function registerPwaUpdateGuard(guard: UpdateGuard): () => void {
  guards.add(guard);
  return () => guards.delete(guard);
}

function readiness(): "ready" | "dirty" | "busy" {
  if (hasPendingApiMutations()) return "busy";
  for (const guard of guards) {
    let state: UpdateReadiness;
    try {
      state = guard();
    } catch {
      return "busy";
    }
    if (state.busy) return "busy";
    if (state.dirty) return "dirty";
  }
  return "ready";
}

function setAppInert(inert: boolean): void {
  if (inert) {
    if (inertSnapshot.length > 0) return;
    const targets = document.querySelectorAll<HTMLElement>(
      ".app-shell, .signed-out-screen, .public-tool-shell, .boot-screen, .error-screen, .skip-link",
    );
    inertSnapshot = [...targets].map((element) => ({
      element,
      wasInert: element.inert,
    }));
    for (const { element } of inertSnapshot) element.inert = true;
    return;
  }

  const snapshot = inertSnapshot;
  inertSnapshot = [];
  for (const { element, wasInert } of snapshot) {
    if (element.isConnected) element.inert = wasInert;
  }
}

function emitState(
  state:
    | "preparing"
    | "committing"
    | "blocked"
    | "cancelled"
    | "activated"
    | "reload-cancelled",
  updateId?: string,
  reason?: string,
): void {
  window.dispatchEvent(
    new CustomEvent("stricknani:pwa-update", {
      detail: { state, updateId, reason },
    }),
  );
}

function getChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === "undefined") return null;
  channel ??= new BroadcastChannel("stricknani-pwa-update-v1");
  if (!channel.onmessage) {
    channel.onmessage = (event: MessageEvent<unknown>) =>
      handlePwaBroadcast(event.data);
  }
  return channel;
}

function readToken(): UpdateToken | null {
  try {
    const stored = sessionStorage.getItem(TOKEN_KEY);
    if (!stored) return null;
    const value: unknown = JSON.parse(stored);
    if (typeof value !== "object" || value === null) return null;
    const token = value as Partial<UpdateToken>;
    if (
      typeof token.updateId !== "string" ||
      !/^[a-f0-9-]{32,36}$/.test(token.updateId) ||
      typeof token.appVersion !== "string" ||
      token.appVersion.length === 0 ||
      token.appVersion.length > 128 ||
      typeof token.workerVersion !== "string" ||
      !/^[a-f0-9]{64}$/.test(token.workerVersion)
    ) {
      return null;
    }
    return {
      updateId: token.updateId,
      appVersion: token.appVersion,
      workerVersion: token.workerVersion,
    };
  } catch {
    return null;
  }
}

function storeToken(token: UpdateToken): boolean {
  try {
    sessionStorage.setItem(TOKEN_KEY, JSON.stringify(token));
    return true;
  } catch {
    return false;
  }
}

function clearToken(updateId?: string): void {
  if (updateId) {
    const stored = readToken();
    if (stored && stored.updateId !== updateId) return;
  }
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // A missing or inaccessible token never grants update permission.
  }
}

function unlock(updateId?: string, keepToken = false): void {
  if (updateId && lockedToken?.updateId !== updateId) return;
  if (prepareTimer !== null) window.clearTimeout(prepareTimer);
  prepareTimer = null;
  if (commitTimer !== null) window.clearTimeout(commitTimer);
  commitTimer = null;
  if (lockedToken && !keepToken) clearToken(lockedToken.updateId);
  const token = lockedToken;
  lockedToken = null;
  setAppInert(false);
  emitState("cancelled", token?.updateId ?? updateId);
}

function cancelEveryTab(updateId: string): void {
  getChannel()?.postMessage({ type: "CANCEL_UPDATE", updateId });
  unlock(updateId);
}

function pageResponse(
  port: MessagePort | undefined,
  type: string,
  detail: Record<string, unknown> = {},
): void {
  port?.postMessage({ type, ...detail });
  port?.close();
}

function safeToken(message: WorkerMessage): UpdateToken | null {
  if (
    typeof message.updateId !== "string" ||
    !/^[a-f0-9-]{32,36}$/.test(message.updateId) ||
    typeof message.appVersion !== "string" ||
    message.appVersion.length === 0 ||
    message.appVersion.length > 128 ||
    typeof message.workerVersion !== "string" ||
    !/^[a-f0-9]{64}$/.test(message.workerVersion)
  ) {
    return null;
  }
  return {
    updateId: message.updateId,
    appVersion: message.appVersion,
    workerVersion: message.workerVersion,
  };
}

function sameToken(left: UpdateToken | null, right: UpdateToken): boolean {
  return (
    left?.updateId === right.updateId &&
    left.appVersion === right.appVersion &&
    left.workerVersion === right.workerVersion
  );
}

function setLocked(token: UpdateToken): boolean {
  if (
    readiness() !== "ready" ||
    (lockedToken && !sameToken(lockedToken, token))
  )
    return false;
  if (!storeToken(token)) return false;
  lockedToken = token;
  setAppInert(true);
  return true;
}

export function handlePwaWorkerMessage(
  event: MessageEvent<WorkerMessage>,
): void {
  const message = event.data;
  const port = event.ports[0];
  if (!message || typeof message.type !== "string") return;

  if (message.type === "PREPARE_UPDATE") {
    const token = safeToken(message);
    if (!token) {
      pageResponse(port, "UPDATE_BLOCKED", { reason: "version" });
      return;
    }
    const state = readiness();
    if (state !== "ready") {
      emitState("blocked", token.updateId, state);
      pageResponse(port, "UPDATE_BLOCKED", { reason: state });
      return;
    }
    if (!setLocked(token)) {
      pageResponse(port, "UPDATE_BLOCKED", { reason: "unavailable" });
      return;
    }
    if (prepareTimer === null) {
      prepareTimer = window.setTimeout(() => {
        prepareTimer = null;
        if (!sameToken(lockedToken, token)) return;
        void navigator.serviceWorker
          .getRegistration("/app/")
          .then((registration) =>
            registration?.waiting
              ? askWorker(
                  registration.waiting,
                  { type: "CANCEL_UPDATE", updateId: token.updateId },
                  2_000,
                )
              : null,
          )
          .catch(() => null)
          .finally(() => {
            if (!sameToken(lockedToken, token)) return;
            unlock(token.updateId);
            emitState("blocked", token.updateId, "timeout");
          });
      }, PREPARE_LOCK_TIMEOUT_MS);
    }
    emitState("preparing", token.updateId);
    pageResponse(port, "UPDATE_PREPARED", { pageVersion: appVersion });
    return;
  }

  if (message.type === "VALIDATE_UPDATE") {
    const token = safeToken(message);
    const ready =
      token !== null &&
      sameToken(lockedToken, token) &&
      sameToken(readToken(), token) &&
      readiness() === "ready";
    pageResponse(
      port,
      ready ? "UPDATE_VALIDATED" : "UPDATE_BLOCKED",
      ready ? { pageVersion: appVersion } : { reason: "changed" },
    );
    return;
  }

  if (
    message.type === "CANCEL_UPDATE" &&
    typeof message.updateId === "string"
  ) {
    unlock(message.updateId);
    pageResponse(port, "UPDATE_CANCELLED");
    return;
  }

  if (message.type === "UPDATE_COMMITTING") {
    const token = safeToken(message);
    if (
      token &&
      sameToken(lockedToken, token) &&
      sameToken(readToken(), token)
    ) {
      if (prepareTimer !== null) window.clearTimeout(prepareTimer);
      prepareTimer = null;
      if (commitTimer !== null) window.clearTimeout(commitTimer);
      commitTimer = window.setTimeout(() => {
        commitTimer = null;
        if (sameToken(lockedToken, token)) {
          unlock(token.updateId, true);
          emitState("reload-cancelled", token.updateId, "timeout");
        }
      }, COMMIT_LOCK_TIMEOUT_MS);
      emitState("committing", token.updateId);
    }
    pageResponse(port, "UPDATE_COMMITTING_SEEN");
    return;
  }

  if (message.type === "QUERY_APP_VERSION") {
    void queryControlledVersion().then((controller) => {
      pageResponse(port, "APP_VERSION", {
        pageVersion: appVersion,
        controllerVersion: controller?.version ?? null,
        controllerAppVersion: controller?.appVersion ?? null,
      });
    });
    return;
  }

  if (message.type === "UPDATE_ACTIVATED") {
    const token = safeToken(message);
    if (
      token &&
      sameToken(lockedToken, token) &&
      sameToken(readToken(), token)
    ) {
      if (prepareTimer !== null) window.clearTimeout(prepareTimer);
      prepareTimer = null;
      if (commitTimer !== null) window.clearTimeout(commitTimer);
      commitTimer = null;
      emitState("activated", token.updateId);
      pageResponse(port, "UPDATE_ACTIVATION_SEEN");
      void attemptPreparedReload(token);
      return;
    }
    pageResponse(port, "UPDATE_ACTIVATION_SEEN");
    return;
  }
}

async function attemptPreparedReload(token: UpdateToken): Promise<void> {
  if (!sameToken(lockedToken, token) || !sameToken(readToken(), token)) return;
  if (readiness() !== "ready") {
    unlock(token.updateId, true);
    emitState("reload-cancelled", token.updateId, readiness());
    return;
  }

  setAppInert(true);
  let pageHiding = false;
  const onPageHide = () => {
    pageHiding = true;
    if (reloadTimer !== null) window.clearTimeout(reloadTimer);
    reloadTimer = null;
  };
  window.addEventListener("pagehide", onPageHide, { once: true });
  window.location.reload();
  reloadTimer = window.setTimeout(() => {
    reloadTimer = null;
    window.removeEventListener("pagehide", onPageHide);
    if (!pageHiding && sameToken(lockedToken, token)) {
      unlock(token.updateId, true);
      emitState("reload-cancelled", token.updateId, "unloaded");
    }
  }, 1_500);
}

export async function retryPreparedReload(
  activeWorker: ServiceWorker | null,
): Promise<"ready" | "busy" | "dirty" | "stale" | "unavailable"> {
  const token = readToken();
  if (!activeWorker || !token) return "stale";
  const status = readiness();
  if (status !== "ready") return status;

  const version = await askWorker(activeWorker, { type: "GET_VERSION" }, 2_000);
  if (
    !version ||
    version.appVersion !== token.appVersion ||
    version.workerVersion !== token.workerVersion
  ) {
    return "stale";
  }
  if (!setLocked(token)) return "unavailable";
  await attemptPreparedReload(token);
  return "ready";
}

export function handlePwaBroadcast(message: unknown): void {
  if (
    typeof message === "object" &&
    message !== null &&
    "type" in message &&
    message.type === "CANCEL_UPDATE" &&
    "updateId" in message &&
    typeof message.updateId === "string"
  ) {
    unlock(message.updateId);
  }
}

export async function confirmReloadedApp(
  activeWorker: ServiceWorker | null,
): Promise<void> {
  if (!activeWorker) return;
  const token = readToken();
  if (!token) {
    clearToken();
    return;
  }
  if (token.appVersion !== appVersion) return;

  const controller = navigator.serviceWorker.controller;
  if (!controller || controller !== activeWorker) return;
  const version = await askWorker(activeWorker, { type: "GET_VERSION" }, 2_000);
  if (
    !version ||
    version.appVersion !== appVersion ||
    version.workerVersion !== token.workerVersion
  ) {
    return;
  }

  const acknowledgement = await askWorker(
    activeWorker,
    {
      type: "RELOAD_ACK",
      updateId: token.updateId,
      appVersion: token.appVersion,
      workerVersion: token.workerVersion,
      pageVersion: appVersion,
    },
    2_000,
  );
  if (acknowledgement?.type === "RELOAD_ACK_ACCEPTED") {
    clearToken(token.updateId);
    lockedToken = null;
    setAppInert(false);
  }
}

export async function askWorker(
  worker: ServiceWorker,
  message: Record<string, unknown>,
  timeoutMs: number,
): Promise<Record<string, unknown> | null> {
  return new Promise((resolve) => {
    const channel = new MessageChannel();
    const timeout = window.setTimeout(() => {
      channel.port1.close();
      resolve(null);
    }, timeoutMs);
    channel.port1.onmessage = (
      event: MessageEvent<Record<string, unknown>>,
    ) => {
      window.clearTimeout(timeout);
      channel.port1.close();
      resolve(event.data);
    };
    try {
      worker.postMessage(message, [channel.port2]);
    } catch {
      window.clearTimeout(timeout);
      channel.port1.close();
      resolve(null);
    }
  });
}

async function queryControlledVersion(): Promise<Record<
  string,
  unknown
> | null> {
  const controller = navigator.serviceWorker?.controller;
  return controller
    ? askWorker(controller, { type: "GET_VERSION" }, 1_000)
    : null;
}

export function currentUpdateId(): string | null {
  return lockedToken?.updateId ?? null;
}

export function cancelLocalUpdate(updateId: string): void {
  cancelEveryTab(updateId);
}

export function updateCoordinatorChannel(): BroadcastChannel | null {
  return getChannel();
}
