/**
 * Browser notifications for this device, opt-in from Account settings.
 *
 * Every notification opens what it is about (AGENTS.md): it carries an app
 * path, and a click focuses an app tab and navigates there in-app, so the
 * tab's unsaved-changes guard still applies. Without an open tab the service
 * worker opens a new window on that path.
 */

import { session } from "#lib/session.svelte.ts";
import { subscribePush, unsubscribePush } from "#lib/web-push.ts";

export const WEB_NOTIFICATIONS_STORAGE_KEY = "stricknani.notifications";
export const OPEN_PATH_MESSAGE = "OPEN_PATH";
const APP_PREFIX = "/app/";

export type NotificationState = "unsupported" | "denied" | "off" | "on";

export interface AppNotification {
  title: string;
  body: string;
  /** Replaces an earlier notification with the same tag instead of stacking. */
  tag: string;
  /** App path the notification opens, for example `/app/projects/3`. */
  path: string;
  /** App path of a thumbnail (`/media/thumbnails/...`) shown as icon and picture. */
  image?: string | null;
  /** Label of the "open" action button, when the browser shows actions. */
  openLabel?: string;
  /** When it happened, in milliseconds since the epoch. */
  timestamp?: number;
}

type PreferenceStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function storage(): PreferenceStorage | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}

function storedEnabled(): boolean {
  try {
    return storage()?.getItem(WEB_NOTIFICATIONS_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

function supported(): boolean {
  return typeof window !== "undefined" && "Notification" in window;
}

function currentPermission(): NotificationPermission | null {
  return supported() ? Notification.permission : null;
}

/** Only same-origin app paths; anything else opens the app's start page. */
export function safeAppPath(path: unknown): string {
  if (typeof path !== "string" || !path.startsWith(APP_PREFIX)) {
    return APP_PREFIX;
  }
  try {
    const url = new URL(path, "https://app.invalid");
    if (url.origin !== "https://app.invalid") return APP_PREFIX;
    if (!url.pathname.startsWith(APP_PREFIX)) return APP_PREFIX;
    return `${url.pathname}${url.search}`;
  } catch {
    return APP_PREFIX;
  }
}

const THUMBNAIL_PREFIX = "/media/thumbnails/";
const MAX_THUMBNAIL_BYTES = 1024 * 1024;
const APP_ICON = `${APP_PREFIX}icons/icon-192.png`;

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

/**
 * Loads a thumbnail with the session cookie and returns it as a data: URL.
 * Thumbnails need the session, and the browser may fetch a notification's
 * image URL without it; a data: URL always loads. Any failure gives null.
 */
export async function thumbnailDataUrl(
  path: string | null | undefined,
  fetchImage: typeof fetch = fetch,
): Promise<string | null> {
  if (typeof path !== "string" || !path.startsWith(THUMBNAIL_PREFIX)) {
    return null;
  }
  try {
    const url = new URL(path, "https://app.invalid");
    if (
      url.origin !== "https://app.invalid" ||
      !url.pathname.startsWith(THUMBNAIL_PREFIX)
    ) {
      return null;
    }
    const response = await fetchImage(url.pathname, {
      credentials: "same-origin",
    });
    const type = response.headers.get("content-type") ?? "";
    if (!response.ok || !type.startsWith("image/")) return null;
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length === 0 || bytes.length > MAX_THUMBNAIL_BYTES) return null;
    return `data:${type.split(";")[0]};base64,${bytesToBase64(bytes)}`;
  } catch {
    return null;
  }
}

/**
 * The options the app passes: TypeScript's DOM types lack the
 * service-worker-only fields (`image`, `timestamp`, `renotify`, `actions`).
 */
export type RichNotificationOptions = NotificationOptions & {
  image?: string;
  timestamp?: number;
  renotify?: boolean;
  actions?: { action: string; title: string }[];
};

/** Notification options for a notification and its loaded picture, if any. */
export function notificationOptions(
  notification: AppNotification,
  picture: string | null,
): RichNotificationOptions {
  const options: RichNotificationOptions = {
    body: notification.body,
    tag: notification.tag,
    renotify: true,
    icon: picture ?? APP_ICON,
    data: { path: safeAppPath(notification.path) },
  };
  if (picture) options.image = picture;
  if (Number.isFinite(notification.timestamp)) {
    options.timestamp = notification.timestamp;
  }
  if (notification.openLabel) {
    options.actions = [{ action: "open", title: notification.openLabel }];
  }
  return options;
}

export function notificationState(
  isSupported: boolean,
  permission: NotificationPermission | null,
  enabled: boolean,
): NotificationState {
  if (!isSupported || permission === null) return "unsupported";
  if (permission === "denied") return "denied";
  return enabled && permission === "granted" ? "on" : "off";
}

let enabled = $state(storedEnabled());
let permission = $state<NotificationPermission | null>(currentPermission());

if (typeof window !== "undefined") {
  try {
    window.addEventListener("storage", (event) => {
      if (event.key === null || event.key === WEB_NOTIFICATIONS_STORAGE_KEY) {
        enabled = storedEnabled();
      }
    });
  } catch {
    // Cross-tab updates are optional.
  }
}

function store(next: boolean): void {
  enabled = next;
  try {
    if (next) storage()?.setItem(WEB_NOTIFICATIONS_STORAGE_KEY, "1");
    else storage()?.removeItem(WEB_NOTIFICATIONS_STORAGE_KEY);
  } catch {
    // Keep the choice for this visit when browser storage is unavailable.
  }
}

export const webNotifications = {
  get state(): NotificationState {
    return notificationState(supported(), permission, enabled);
  },

  /** Asks the browser for permission (from a click) and turns notifications on. */
  async enable(): Promise<NotificationState> {
    if (!supported()) return "unsupported";
    let result = Notification.permission;
    if (result === "default") {
      try {
        result = await Notification.requestPermission();
      } catch {
        result = Notification.permission;
      }
    }
    permission = result;
    store(result === "granted");
    if (result === "granted") void this.syncPush();
    return this.state;
  },

  disable(): void {
    store(false);
    const csrf = session.data?.csrf_token;
    if (csrf) void unsubscribePush(csrf).catch(() => undefined);
  },

  /**
   * Keeps this browser's push subscription registered while notifications are
   * on, so reminders also arrive with no tab open. Best effort: without Web
   * Push, reminders still show while a tab is open.
   */
  async syncPush(): Promise<boolean> {
    const csrf = session.data?.csrf_token;
    if (this.state !== "on" || !csrf) return false;
    try {
      return await subscribePush(csrf);
    } catch {
      return false;
    }
  },

  /** Re-reads the browser permission, which the user can change at any time. */
  refresh(): void {
    permission = currentPermission();
    enabled = storedEnabled();
  },

  /** Shows a notification when they are on; returns whether one was shown. */
  async show(notification: AppNotification): Promise<boolean> {
    this.refresh();
    if (this.state !== "on") return false;
    const path = safeAppPath(notification.path);
    const options = notificationOptions(
      notification,
      await thumbnailDataUrl(notification.image),
    );
    try {
      const registration =
        "serviceWorker" in navigator
          ? await navigator.serviceWorker.getRegistration(APP_PREFIX)
          : undefined;
      if (registration) {
        await registration.showNotification(notification.title, options);
        return true;
      }
      // No worker (development, unsupported context): a page notification.
      const shown = new Notification(notification.title, options);
      shown.onclick = () => {
        window.focus();
        openPath(path);
        shown.close();
      };
      return true;
    } catch {
      return false;
    }
  },
};

let navigateTo: ((path: string) => void) | null = null;

function openPath(path: string): void {
  if (navigateTo) navigateTo(safeAppPath(path));
  else window.location.assign(safeAppPath(path));
}

/**
 * Lets a notification click navigate this tab in-app. Returns the cleanup.
 */
export function listenForNotificationOpens(
  navigate: (path: string) => void,
): () => void {
  navigateTo = navigate;
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) {
    return () => {
      navigateTo = null;
    };
  }
  const onMessage = (event: MessageEvent) => {
    const data: unknown = event.data;
    if (
      typeof data === "object" &&
      data !== null &&
      (data as { type?: unknown }).type === OPEN_PATH_MESSAGE
    ) {
      navigate(safeAppPath((data as { path?: unknown }).path));
    }
  };
  navigator.serviceWorker.addEventListener("message", onMessage);
  return () => {
    navigateTo = null;
    navigator.serviceWorker.removeEventListener("message", onMessage);
  };
}
