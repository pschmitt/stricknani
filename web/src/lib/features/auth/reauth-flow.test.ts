import { describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import { PasskeyCeremonyError } from "./passkey-api";
import {
  CLOSED,
  PENDING_REAUTH_KEY,
  REAUTH_WINDOW_MS,
  classifyReauthFailure,
  isBusy,
  reauthReducer,
  savePendingReauth,
  takePendingReauth,
  type ReauthEvent,
  type ReauthState,
} from "./reauth-flow";

vi.mock("#lib/i18n.svelte.ts", () => ({
  t: (message: string) => message,
}));

const request = {
  action: "unlink_identity" as const,
  target: { identity_id: 4 },
};

function run(events: ReauthEvent[], from: ReauthState = CLOSED): ReauthState {
  return events.reduce(reauthReducer, from);
}

describe("reauth state machine", () => {
  it("walks open, submit, password_confirmed", () => {
    expect(run([{ type: "open", request }])).toEqual({
      phase: "prompt",
      request,
      failure: null,
    });
    expect(
      run([
        { type: "open", request },
        { type: "submit", method: "password" },
      ]),
    ).toEqual({ phase: "verifying", request, method: "password" });
    expect(
      run([
        { type: "open", request },
        { type: "submit", method: "password" },
        { type: "password_confirmed" },
      ]),
    ).toEqual({ phase: "confirmed", request });
  });

  it("returns to the prompt with the failure after a wrong password", () => {
    const state = run([
      { type: "open", request },
      { type: "submit", method: "password" },
      { type: "failed", failure: "invalid_credentials" },
    ]);
    expect(state).toEqual({
      phase: "prompt",
      request,
      failure: "invalid_credentials",
    });
    // The user can retry from there.
    expect(run([{ type: "submit", method: "password" }], state).phase).toBe(
      "verifying",
    );
  });

  it("closes on an expired session instead of prompting again", () => {
    expect(
      run([
        { type: "open", request },
        { type: "submit", method: "password" },
        { type: "failed", failure: "session_expired" },
      ]),
    ).toEqual(CLOSED);
  });

  it("provider confirmation ends in redirecting and cannot be cancelled", () => {
    const state = run([
      { type: "open", request },
      { type: "submit", method: "oidc" },
      { type: "provider_started" },
    ]);
    expect(state).toEqual({ phase: "redirecting", request });
    expect(isBusy(state)).toBe(true);
    expect(reauthReducer(state, { type: "cancel" })).toBe(state);
  });

  it("ignores events that do not fit the current phase", () => {
    expect(run([{ type: "submit", method: "password" }])).toEqual(CLOSED);
    expect(run([{ type: "password_confirmed" }])).toEqual(CLOSED);
    expect(run([{ type: "failed", failure: "unknown" }])).toEqual(CLOSED);
    const verifying = run([
      { type: "open", request },
      { type: "submit", method: "password" },
    ]);
    expect(reauthReducer(verifying, { type: "cancel" })).toBe(verifying);
    expect(reauthReducer(verifying, { type: "open", request })).toBe(verifying);
    expect(reauthReducer(verifying, { type: "provider_started" })).toBe(
      verifying,
    );
  });

  it("cancels from the prompt", () => {
    expect(run([{ type: "open", request }, { type: "cancel" }])).toEqual(
      CLOSED,
    );
  });
});

describe("classifyReauthFailure", () => {
  const error = (code: string, status = 400) =>
    new ApiError("msg", status, code);

  it("maps contract codes", () => {
    expect(classifyReauthFailure(error("invalid_credentials", 401))).toBe(
      "invalid_credentials",
    );
    expect(classifyReauthFailure(error("session_expired", 401))).toBe(
      "session_expired",
    );
    expect(classifyReauthFailure(error("rate_limited", 429))).toBe(
      "rate_limited",
    );
    expect(classifyReauthFailure(error("oidc_unavailable", 503))).toBe(
      "unavailable",
    );
    expect(classifyReauthFailure(error("network_error", 0))).toBe(
      "unavailable",
    );
    expect(classifyReauthFailure(error("validation_error", 422))).toBe(
      "unknown",
    );
    expect(classifyReauthFailure(new Error("x"))).toBe("unknown");
  });
});

function memoryStorage() {
  const data = new Map<string, string>();
  return {
    data,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
  };
}

describe("pending provider reauth", () => {
  it("round-trips the action once and keeps no proof", () => {
    const storage = memoryStorage();
    savePendingReauth(storage, request, 1_000);
    expect(storage.data.get(PENDING_REAUTH_KEY)).not.toMatch(
      /token|proof|code/i,
    );
    expect(takePendingReauth(storage, 2_000)).toEqual(request);
    expect(takePendingReauth(storage, 2_000)).toBeNull();
  });

  it("leaves a request for its owner when the filter rejects it", () => {
    const storage = memoryStorage();
    const deletion = { action: "delete_account" as const };
    savePendingReauth(storage, deletion, 1_000);
    expect(
      takePendingReauth(
        storage,
        2_000,
        (action) => action !== "delete_account",
      ),
    ).toBeNull();
    expect(storage.data.has(PENDING_REAUTH_KEY)).toBe(true);
    expect(
      takePendingReauth(
        storage,
        2_000,
        (action) => action === "delete_account",
      ),
    ).toEqual(deletion);
    expect(storage.data.has(PENDING_REAUTH_KEY)).toBe(false);
  });

  it("expires with the ten minute window", () => {
    const storage = memoryStorage();
    savePendingReauth(storage, request, 0);
    expect(takePendingReauth(storage, REAUTH_WINDOW_MS + 1)).toBeNull();
  });

  it("ignores tampered or unknown values and unavailable storage", () => {
    const storage = memoryStorage();
    storage.setItem(PENDING_REAUTH_KEY, "not json");
    expect(takePendingReauth(storage)).toBeNull();
    storage.setItem(
      PENDING_REAUTH_KEY,
      JSON.stringify({ request: { action: "rm_rf" }, at: Date.now() }),
    );
    expect(takePendingReauth(storage)).toBeNull();
    expect(takePendingReauth(undefined)).toBeNull();
    expect(() => savePendingReauth(undefined, request)).not.toThrow();
    const broken = {
      ...memoryStorage(),
      setItem: () => {
        throw new Error("quota");
      },
    };
    expect(() => savePendingReauth(broken, request)).not.toThrow();
  });
});

describe("passkey step-up", () => {
  const manage = { action: "manage_passkeys" as const };

  it("walks open, submit with a passkey, passkey_confirmed", () => {
    expect(
      run([
        { type: "open", request: manage },
        { type: "submit", method: "passkey" },
        { type: "passkey_confirmed" },
      ]),
    ).toEqual({ phase: "confirmed", request: manage });
  });

  it("only a passkey attempt can be confirmed by a passkey", () => {
    const verifying = run([
      { type: "open", request: manage },
      { type: "submit", method: "password" },
    ]);
    expect(reauthReducer(verifying, { type: "passkey_confirmed" })).toBe(
      verifying,
    );
    expect(reauthReducer(CLOSED, { type: "passkey_confirmed" })).toBe(CLOSED);
  });

  it("returns to the prompt with the failure after a rejected passkey", () => {
    expect(
      run([
        { type: "open", request: manage },
        { type: "submit", method: "passkey" },
        { type: "failed", failure: "invalid_passkey" },
      ]),
    ).toEqual({ phase: "prompt", request: manage, failure: "invalid_passkey" });
  });

  it("classifies passkey failures", () => {
    expect(
      classifyReauthFailure(new ApiError("x", 401, "invalid_passkey")),
    ).toBe("invalid_passkey");
    expect(
      classifyReauthFailure(new ApiError("x", 400, "invalid_challenge")),
    ).toBe("invalid_passkey");
    expect(
      classifyReauthFailure(new PasskeyCeremonyError("cancelled")),
    ).toBe("passkey_cancelled");
    expect(
      classifyReauthFailure(new PasskeyCeremonyError("unsupported")),
    ).toBe("unavailable");
  });

  it("remembers a manage_passkeys request across a provider redirect", () => {
    const store = new Map<string, string>();
    const storage = {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
    };
    savePendingReauth(storage, manage, 1000);
    expect(takePendingReauth(storage, 2000)).toEqual({
      action: "manage_passkeys",
    });
  });
});
