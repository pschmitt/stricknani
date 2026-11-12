import { describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import { PasskeyCeremonyError } from "./passkey-api";
import {
  classifyPasskeyLoginFailure,
  runConditionalSignIn,
} from "./passkey-login";

vi.mock("#lib/i18n.svelte.ts", () => ({
  t: (message: string) => message,
}));

describe("classifyPasskeyLoginFailure", () => {
  it.each([
    [new PasskeyCeremonyError("cancelled"), "cancelled"],
    [new DOMException("x", "AbortError"), "cancelled"],
    [new PasskeyCeremonyError("unavailable"), "unavailable"],
    [new PasskeyCeremonyError("unknown"), "unknown"],
    [new ApiError("x", 401, "invalid_passkey"), "not_recognized"],
    [new ApiError("x", 429, "rate_limited"), "rate_limited"],
    [new ApiError("x", 404, "passkeys_unavailable"), "unavailable"],
    [new ApiError("x", 500, "internal_error"), "unknown"],
    [new Error("x"), "unknown"],
  ])("maps %s to %s", (error, expected) => {
    expect(classifyPasskeyLoginFailure(error)).toBe(expected);
  });
});

describe("runConditionalSignIn", () => {
  const base = () => ({
    renew: vi.fn().mockResolvedValue(undefined),
    onSignedIn: vi.fn(),
    onFailure: vi.fn(),
  });

  it("signs in once a passkey is chosen", async () => {
    const deps = base();
    const signIn = vi.fn().mockResolvedValue(undefined);
    await runConditionalSignIn({
      ...deps,
      signIn,
      signal: new AbortController().signal,
    });
    expect(signIn).toHaveBeenCalledTimes(1);
    expect(signIn.mock.calls[0][0].conditional).toBe(true);
    expect(deps.onSignedIn).toHaveBeenCalledTimes(1);
    expect(deps.onFailure).not.toHaveBeenCalled();
  });

  it("restarts after the CSRF renewal interval and keeps listening", async () => {
    vi.useFakeTimers();
    try {
      const deps = base();
      const rounds: AbortSignal[] = [];
      const signIn = vi
        .fn()
        .mockImplementationOnce(
          ({ signal }: { signal: AbortSignal }) =>
            new Promise((_, reject) => {
              rounds.push(signal);
              signal.addEventListener("abort", () =>
                reject(new DOMException("x", "AbortError")),
              );
            }),
        )
        .mockResolvedValueOnce(undefined);
      const done = runConditionalSignIn({
        ...deps,
        signIn,
        signal: new AbortController().signal,
        renewAfterMs: 1000,
      });
      await vi.advanceTimersByTimeAsync(1000);
      await done;
      expect(rounds[0].aborted).toBe(true);
      expect(deps.renew).toHaveBeenCalledTimes(1);
      expect(signIn).toHaveBeenCalledTimes(2);
      expect(deps.onSignedIn).toHaveBeenCalledTimes(1);
      expect(deps.onFailure).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a rejected passkey and listens again", async () => {
    const deps = base();
    const signIn = vi
      .fn()
      .mockRejectedValueOnce(new ApiError("x", 401, "invalid_passkey"))
      .mockResolvedValueOnce(undefined);
    await runConditionalSignIn({
      ...deps,
      signIn,
      signal: new AbortController().signal,
    });
    expect(deps.onFailure).toHaveBeenCalledWith("not_recognized");
    expect(deps.renew).toHaveBeenCalledTimes(1);
    expect(deps.onSignedIn).toHaveBeenCalledTimes(1);
  });

  it("stops on rate limiting and when the page goes away", async () => {
    const deps = base();
    await runConditionalSignIn({
      ...deps,
      signIn: vi.fn().mockRejectedValue(new ApiError("x", 429, "rate_limited")),
      signal: new AbortController().signal,
    });
    expect(deps.onFailure).toHaveBeenCalledWith("rate_limited");
    expect(deps.onSignedIn).not.toHaveBeenCalled();

    const controller = new AbortController();
    const quiet = base();
    const signIn = vi
      .fn()
      .mockImplementation(
        ({ signal }: { signal: AbortSignal }) =>
          new Promise((_, reject) =>
            signal.addEventListener("abort", () =>
              reject(new DOMException("x", "AbortError")),
            ),
          ),
      );
    const running = runConditionalSignIn({
      ...quiet,
      signIn,
      signal: controller.signal,
    });
    controller.abort();
    await running;
    expect(quiet.onFailure).not.toHaveBeenCalled();
    expect(quiet.renew).not.toHaveBeenCalled();
  });

  it("gives up instead of spinning when ceremonies keep ending at once", async () => {
    const deps = base();
    const signIn = vi
      .fn()
      .mockRejectedValue(new PasskeyCeremonyError("cancelled"));
    await runConditionalSignIn({
      ...deps,
      signIn,
      signal: new AbortController().signal,
    });
    expect(signIn).toHaveBeenCalledTimes(2);
    expect(deps.onSignedIn).not.toHaveBeenCalled();
  });

  it("stops quietly when the browser cannot do autofill passkeys", async () => {
    for (const failure of ["unknown", "unsupported", "unavailable"] as const) {
      const deps = base();
      const signIn = vi
        .fn()
        .mockRejectedValue(new PasskeyCeremonyError(failure));
      await runConditionalSignIn({
        ...deps,
        signIn,
        signal: new AbortController().signal,
      });
      expect(signIn).toHaveBeenCalledTimes(1);
      expect(deps.onFailure).not.toHaveBeenCalled();
      expect(deps.onSignedIn).not.toHaveBeenCalled();
    }
  });
});
