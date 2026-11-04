import { ApiError } from "#lib/api.ts";
import type { ReauthRequest } from "./oidc-api";

/**
 * State machine for the shared re-authentication dialog. The proof itself
 * lives on the server (browser mode only sets a session stamp), so this holds
 * nothing but the requested action and a failure kind.
 */

export type ReauthMethod = "password" | "oidc";

export type ReauthFailure =
  "invalid_credentials" | "rate_limited" | "unavailable" | "unknown";

export type ReauthState =
  | { phase: "closed" }
  | { phase: "prompt"; request: ReauthRequest; failure: ReauthFailure | null }
  | { phase: "verifying"; request: ReauthRequest; method: ReauthMethod }
  | { phase: "confirmed"; request: ReauthRequest }
  | { phase: "redirecting"; request: ReauthRequest };

export type ReauthEvent =
  | { type: "open"; request: ReauthRequest }
  | { type: "submit"; method: ReauthMethod }
  | { type: "password_confirmed" }
  | { type: "provider_started" }
  | { type: "failed"; failure: ReauthFailure | "session_expired" }
  | { type: "cancel" };

export const CLOSED: ReauthState = { phase: "closed" };

export function reauthReducer(
  state: ReauthState,
  event: ReauthEvent,
): ReauthState {
  switch (event.type) {
    case "open":
      return state.phase === "closed"
        ? { phase: "prompt", request: event.request, failure: null }
        : state;
    case "submit":
      return state.phase === "prompt"
        ? { phase: "verifying", request: state.request, method: event.method }
        : state;
    case "password_confirmed":
      return state.phase === "verifying" && state.method === "password"
        ? { phase: "confirmed", request: state.request }
        : state;
    case "provider_started":
      return state.phase === "verifying" && state.method === "oidc"
        ? { phase: "redirecting", request: state.request }
        : state;
    case "failed":
      if (state.phase !== "verifying") return state;
      return event.failure === "session_expired"
        ? CLOSED
        : { phase: "prompt", request: state.request, failure: event.failure };
    case "cancel":
      return state.phase === "prompt" || state.phase === "confirmed"
        ? CLOSED
        : state;
  }
}

export function classifyReauthFailure(
  error: unknown,
): ReauthFailure | "session_expired" {
  if (!(error instanceof ApiError)) return "unknown";
  switch (error.code) {
    case "invalid_credentials":
      return "invalid_credentials";
    case "session_expired":
      return "session_expired";
    case "rate_limited":
      return "rate_limited";
    case "oidc_unavailable":
    case "oidc_disabled":
    case "network_error":
      return "unavailable";
    default:
      return "unknown";
  }
}

export function isBusy(state: ReauthState): boolean {
  return state.phase === "verifying" || state.phase === "redirecting";
}

/**
 * Remember which action a provider re-authentication was started for, so the
 * Account page can offer to continue after the redirect back. Only the action
 * and its ids are stored, never a proof or token.
 */
export const PENDING_REAUTH_KEY = "stricknani.pending-reauth";
export const REAUTH_WINDOW_MS = 10 * 60 * 1000;

export interface ReauthStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function savePendingReauth(
  storage: ReauthStorage | undefined,
  request: ReauthRequest,
  now: number = Date.now(),
): void {
  try {
    storage?.setItem(PENDING_REAUTH_KEY, JSON.stringify({ request, at: now }));
  } catch {
    // Private mode: the user simply repeats the action after returning.
  }
}

const ACTIONS = new Set([
  "link_identity",
  "unlink_identity",
  "admin_unlink_identity",
  "change_password",
  "create_api_token",
  "revoke_api_token",
  "delete_account",
]);

/**
 * Read and clear the pending request. When `accept` is given, a stored
 * request it rejects is left in place for the component that owns it (the
 * Account page has two: linked logins and account deletion).
 */
export function takePendingReauth(
  storage: ReauthStorage | undefined,
  now: number = Date.now(),
  accept: (action: ReauthRequest["action"]) => boolean = () => true,
): ReauthRequest | null {
  try {
    const raw = storage?.getItem(PENDING_REAUTH_KEY);
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    const stored = (value as { request?: { action?: unknown } } | null)?.request
      ?.action;
    if (
      typeof stored === "string" &&
      ACTIONS.has(stored) &&
      !accept(stored as ReauthRequest["action"])
    )
      return null;
    storage?.removeItem(PENDING_REAUTH_KEY);
    if (typeof value !== "object" || value === null) return null;
    const { request, at } = value as { request?: unknown; at?: unknown };
    if (typeof at !== "number" || now - at > REAUTH_WINDOW_MS || now < at)
      return null;
    if (typeof request !== "object" || request === null) return null;
    const candidate = request as { action?: unknown; target?: unknown };
    if (typeof candidate.action !== "string" || !ACTIONS.has(candidate.action))
      return null;
    const target = candidate.target;
    const clean: ReauthRequest = {
      action: candidate.action as ReauthRequest["action"],
    };
    if (typeof target === "object" && target !== null) {
      const t = target as Record<string, unknown>;
      clean.target = {
        ...(typeof t.provider === "string" ? { provider: t.provider } : {}),
        ...(Number.isSafeInteger(t.identity_id)
          ? { identity_id: t.identity_id as number }
          : {}),
        ...(Number.isSafeInteger(t.user_id)
          ? { user_id: t.user_id as number }
          : {}),
      };
    }
    return clean;
  } catch {
    return null;
  }
}
