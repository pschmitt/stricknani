import { ApiError } from "#lib/api.ts";
import { PasskeyCeremonyError } from "./passkey-api";

/**
 * Sign-in with a passkey from the login page: the explicit button and the
 * autofill (conditional mediation) request that runs in the background while
 * the page is open. Kept apart from the Svelte component so the loop can be
 * tested with fakes.
 */

/** The login CSRF token lives ten minutes; renew well before it runs out. */
export const CONDITIONAL_RENEW_MS = 8 * 60 * 1000;

export type PasskeyLoginFailure =
  | "cancelled"
  | "not_recognized"
  | "rate_limited"
  | "unavailable"
  | "host_unsupported"
  | "unknown";

export function classifyPasskeyLoginFailure(
  error: unknown,
): PasskeyLoginFailure {
  if (error instanceof DOMException && error.name === "AbortError")
    return "cancelled";
  if (error instanceof PasskeyCeremonyError) {
    if (error.failure === "cancelled") return "cancelled";
    if (error.failure === "unavailable" || error.failure === "unsupported")
      return "unavailable";
    return "unknown";
  }
  if (error instanceof ApiError) {
    if (error.code === "invalid_passkey") return "not_recognized";
    if (error.code === "rate_limited") return "rate_limited";
    if (error.code === "passkeys_unavailable") return "unavailable";
    if (error.code === "passkey_host_unsupported") return "host_unsupported";
  }
  return "unknown";
}

export interface ConditionalLoginDeps {
  /** One conditional ceremony: resolves when a passkey was chosen and signed in. */
  signIn: (options: {
    conditional: true;
    signal: AbortSignal;
  }) => Promise<void>;
  /** Renew the login CSRF token before the next ceremony. */
  renew: () => Promise<void>;
  onSignedIn: () => void | Promise<void>;
  signal: AbortSignal;
  renewAfterMs?: number;
  /** Test hook. */
  now?: () => number;
}

/**
 * Keep an autofill passkey request pending until the person signs in or the
 * page goes away. A ceremony is restarted when it times out (or just before the
 * CSRF token would) and after a failed attempt, so the autofill entry keeps
 * working; two ceremonies that end almost immediately stop the loop rather than
 * spinning.
 */
export async function runConditionalSignIn(
  deps: ConditionalLoginDeps,
): Promise<void> {
  const now = deps.now ?? Date.now;
  const renewAfter = deps.renewAfterMs ?? CONDITIONAL_RENEW_MS;
  let quickEnds = 0;
  while (!deps.signal.aborted) {
    const round = new AbortController();
    const stop = () => round.abort();
    deps.signal.addEventListener("abort", stop, { once: true });
    const timer = setTimeout(stop, renewAfter);
    const started = now();
    try {
      await deps.signIn({ conditional: true, signal: round.signal });
      await deps.onSignedIn();
      return;
    } catch (error) {
      if (deps.signal.aborted) return;
      const failure = classifyPasskeyLoginFailure(error);
      // Conditional mediation runs in the background. Any failure, including a host mismatch or
      // a server/network error, must stay quiet; only the explicit button reports errors.
      if (failure !== "cancelled") return;
      quickEnds = now() - started < 2000 ? quickEnds + 1 : 0;
      if (quickEnds >= 2) return;
    } finally {
      clearTimeout(timer);
      deps.signal.removeEventListener("abort", stop);
    }
    try {
      await deps.renew();
    } catch {
      return;
    }
  }
}
