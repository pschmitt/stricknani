/**
 * Web Push: lets the server deliver timer reminders while no Stricknani tab
 * is open. The browser subscribes with the server's VAPID key; the project
 * timer sends its reminder schedule so the server knows when one is due.
 */
import { apiRequest } from "#lib/api.ts";

const APP_PREFIX = "/app/";

export interface TimerReminderSchedule {
  /** The run the schedule belongs to (the timer's started_at). */
  started_at: string;
  interval_minutes: 30 | 60 | 120;
  /** When the next reminder is due. */
  next_at: string;
  locale: "en" | "de";
}

/** Decodes the server's base64url (no padding) application server key. */
export function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function sameKey(current: ArrayBuffer | null, wanted: Uint8Array): boolean {
  if (!current) return false;
  const bytes = new Uint8Array(current);
  return (
    bytes.length === wanted.length &&
    bytes.every((value, index) => value === wanted[index])
  );
}

/** Whether this browser can receive Web Push at all. */
export function pushSupported(): boolean {
  return (
    typeof window !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window
  );
}

async function registration(): Promise<ServiceWorkerRegistration | null> {
  if (!pushSupported()) return null;
  return (await navigator.serviceWorker.getRegistration(APP_PREFIX)) ?? null;
}

async function serverKey(): Promise<Uint8Array<ArrayBuffer>> {
  const payload: unknown = await apiRequest("/api/v2/push/key");
  const key =
    typeof payload === "object" &&
    payload !== null &&
    "data" in payload &&
    typeof payload.data === "object" &&
    payload.data !== null &&
    "public_key" in payload.data
      ? payload.data.public_key
      : null;
  if (typeof key !== "string") throw new Error("invalid push key");
  return base64UrlToBytes(key);
}

/**
 * Subscribes this browser (or refreshes its subscription) and registers it
 * with the server. Returns false where Web Push is unavailable.
 */
export async function subscribePush(csrfToken: string): Promise<boolean> {
  const worker = await registration();
  if (!worker) return false;
  const key = await serverKey();
  let subscription = await worker.pushManager.getSubscription();
  // A subscription made with another server key cannot receive our pushes.
  if (
    subscription &&
    !sameKey(subscription.options.applicationServerKey, key)
  ) {
    await subscription.unsubscribe();
    subscription = null;
  }
  subscription ??= await worker.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: key,
  });
  await apiRequest("/api/v2/push/subscription", {
    method: "PUT",
    csrfToken,
    body: subscription.toJSON(),
  });
  return true;
}

/** Drops this browser's subscription here and on the server. */
export async function unsubscribePush(csrfToken: string): Promise<void> {
  const worker = await registration();
  const subscription = await worker?.pushManager.getSubscription();
  if (!subscription) return;
  const { endpoint } = subscription;
  await subscription.unsubscribe();
  await apiRequest("/api/v2/push/subscription", {
    method: "DELETE",
    csrfToken,
    body: { endpoint },
  });
}

/** Stores (or with null, removes) this user's reminder schedule for a timer. */
export async function saveTimerReminder(
  projectId: number,
  csrfToken: string,
  schedule: TimerReminderSchedule | null,
): Promise<void> {
  await apiRequest(`/api/v2/projects/${projectId}/timer/reminder`, {
    method: schedule ? "PUT" : "DELETE",
    csrfToken,
    body: schedule ?? undefined,
  });
}

/**
 * The schedule for a running timer, or null when no reminder applies.
 * `afterSeconds` is the run time at which the next reminder is due.
 */
export function timerReminderSchedule(
  startedAt: string | null,
  intervalMinutes: number,
  afterSeconds: number | null,
  locale: string,
): TimerReminderSchedule | null {
  if (!startedAt) return null;
  if (
    intervalMinutes !== 30 &&
    intervalMinutes !== 60 &&
    intervalMinutes !== 120
  )
    return null;
  const started = Date.parse(startedAt);
  if (!Number.isFinite(started)) return null;
  const after = afterSeconds ?? intervalMinutes * 60;
  return {
    started_at: startedAt,
    interval_minutes: intervalMinutes,
    next_at: new Date(started + after * 1000).toISOString(),
    locale: locale === "de" ? "de" : "en",
  };
}
