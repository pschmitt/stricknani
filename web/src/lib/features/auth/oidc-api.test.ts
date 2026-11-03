import { afterEach, describe, expect, it, vi } from "vitest";
import {
  advanceAuthEpoch,
  ApiError,
  setUnauthorizedHandler,
} from "#lib/api.ts";
import {
  adminUnlinkIdentity,
  cleanOidcQuery,
  getAuthOptions,
  isReauthRequired,
  listIdentities,
  oidcLoginErrorMessage,
  parseAuthOptions,
  parseIdentityList,
  parseStartResponse,
  readOidcError,
  readOidcLinked,
  reauthWithPassword,
  startLinkIdentity,
  startOidcLogin,
  startOidcReauth,
  unlinkIdentity,
} from "./oidc-api";

vi.mock("#lib/i18n.svelte.ts", () => ({
  t: (message: string) => message,
}));

afterEach(() => {
  vi.unstubAllGlobals();
  setUnauthorizedHandler(() => undefined);
});

function json(status: number, body: unknown, headers: HeadersInit = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

const identity = {
  id: 1,
  provider: "authelia",
  subject_hint: "id:abcd1234",
  email: null,
  linked_at: "2026-11-03T12:00:00.000000Z",
  can_unlink: true,
};

describe("parseAuthOptions", () => {
  it("reads the additive auth fragment", () => {
    expect(
      parseAuthOptions({
        data: {
          version: "1",
          auth: {
            password: true,
            oidc: [{ id: "authelia", name: "Authelia" }],
          },
        },
      }),
    ).toEqual({
      password: true,
      oidc: [{ id: "authelia", name: "Authelia" }],
    });
  });

  it("degrades to no providers when the fragment is absent or malformed", () => {
    const none = { password: true, oidc: [] };
    expect(parseAuthOptions({ data: { version: "1" } })).toEqual(none);
    expect(parseAuthOptions(null)).toEqual(none);
    expect(parseAuthOptions({ data: { auth: "x" } })).toEqual(none);
    expect(parseAuthOptions({ data: { auth: { oidc: "x" } } })).toEqual(none);
    expect(parseAuthOptions({ data: { auth: { oidc: [] } } })).toEqual(none);
  });

  it("drops invalid and duplicate providers", () => {
    const parsed = parseAuthOptions({
      data: {
        auth: {
          password: false,
          oidc: [
            { id: "a b", name: "Bad id" },
            { id: "ok", name: "  " },
            { id: "ok", name: "Fine" },
            { id: "ok", name: "Twice" },
            { id: 3, name: "Number" },
            null,
          ],
        },
      },
    });
    expect(parsed).toEqual({
      password: false,
      oidc: [{ id: "ok", name: "Fine" }],
    });
  });

  it("getAuthOptions swallows failures so the sign-in page stays unchanged", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          json(500, { error: { code: "internal_error", message: "x" } }),
        ),
    );
    expect(await getAuthOptions()).toEqual({ password: true, oidc: [] });
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    expect(await getAuthOptions()).toEqual({ password: true, oidc: [] });
  });
});

describe("parseStartResponse", () => {
  it("accepts an https authorization url", () => {
    expect(
      parseStartResponse({
        data: {
          authorization_url: "https://auth.example.test/authorize?x=1",
          expires_at: "2026-11-03T12:10:00Z",
        },
      }),
    ).toEqual({
      authorizationUrl: "https://auth.example.test/authorize?x=1",
      expiresAt: "2026-11-03T12:10:00Z",
    });
  });

  it("allows http only for local development hosts", () => {
    expect(() =>
      parseStartResponse({
        data: { authorization_url: "http://localhost:9091/a" },
      }),
    ).not.toThrow();
    expect(() =>
      parseStartResponse({
        data: { authorization_url: "http://auth.example.test/a" },
      }),
    ).toThrow(ApiError);
  });

  it.each([
    "javascript:alert(1)",
    "not a url",
    "https://user:pw@auth.example.test/",
    "data:text/html,x",
  ])("rejects %s", (url) => {
    expect(() =>
      parseStartResponse({ data: { authorization_url: url } }),
    ).toThrow(ApiError);
  });

  it("rejects a missing envelope", () => {
    expect(() => parseStartResponse({})).toThrow(ApiError);
    expect(() => parseStartResponse({ data: {} })).toThrow(ApiError);
  });
});

describe("parseIdentityList", () => {
  it("reads identities and password_enabled", () => {
    expect(
      parseIdentityList({
        data: [identity],
        meta: { password_enabled: false },
      }),
    ).toEqual({ identities: [identity], passwordEnabled: false });
    expect(parseIdentityList({ data: [] }).passwordEnabled).toBe(true);
  });

  it("rejects malformed identities", () => {
    expect(() => parseIdentityList({ data: [{ ...identity, id: 0 }] })).toThrow(
      ApiError,
    );
    expect(() =>
      parseIdentityList({ data: [{ ...identity, email: 4 }] }),
    ).toThrow(ApiError);
    expect(() =>
      parseIdentityList({ data: [{ ...identity, can_unlink: "yes" }] }),
    ).toThrow(ApiError);
    expect(() => parseIdentityList({ data: "no" })).toThrow(ApiError);
  });
});

describe("oidc_error query handling", () => {
  it("maps the contract values", () => {
    expect(readOidcError("?oidc_error=unlinked")).toBe("unlinked");
    expect(readOidcError("?oidc_error=failed")).toBe("failed");
    expect(readOidcError("?oidc_error=anything-else")).toBe("failed");
    expect(readOidcError("?created=1")).toBeNull();
    expect(readOidcError("")).toBeNull();
  });

  it("reads the linked marker", () => {
    expect(readOidcLinked("?oidc=linked")).toBe(true);
    expect(readOidcLinked("?oidc=other")).toBe(false);
  });

  it("cleans the result parameters and keeps the rest", () => {
    expect(cleanOidcQuery("https://x.test/app/login?oidc_error=unlinked")).toBe(
      "/app/login",
    );
    expect(
      cleanOidcQuery("https://x.test/app/account?oidc=linked&tab=a#top"),
    ).toBe("/app/account?tab=a#top");
    expect(cleanOidcQuery("https://x.test/app/login?created=1")).toBeNull();
  });

  it("words the messages neutrally with the provider name", () => {
    const unlinked = oidcLoginErrorMessage("unlinked", "en", "Authelia");
    expect(unlinked).toContain("No Stricknani account is linked");
    expect(unlinked).toContain("link Authelia in your account settings");
    expect(unlinked).not.toContain("{name}");
    expect(oidcLoginErrorMessage("failed", "en", "Authelia")).toContain(
      "Try again",
    );
  });
});

describe("requests", () => {
  it("starts a browser login with CSRF and no secrets in the url", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json(200, {
        data: {
          authorization_url: "https://auth.example.test/a",
          expires_at: "x",
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    await startOidcLogin("authelia", "csrf-1", "/app/projects");
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe("/api/v2/auth/oidc/start");
    expect(init.method).toBe("POST");
    expect((init.headers as Headers).get("x-csrf-token")).toBe("csrf-1");
    expect(JSON.parse(init.body as string)).toEqual({
      provider: "authelia",
      client: "browser",
      return_to: "/app/projects",
    });
  });

  it("sends the reauth password and target", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        json(200, { data: { reauthenticated: true, expires_at: "x" } }),
      );
    vi.stubGlobal("fetch", fetchMock);
    await reauthWithPassword(
      "hunter2",
      {
        action: "admin_unlink_identity",
        target: { user_id: 5, identity_id: 9 },
      },
      "csrf-2",
    );
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe("/api/v2/account/reauth");
    expect(JSON.parse(init.body as string)).toEqual({
      password: "hunter2",
      action: "admin_unlink_identity",
      target: { user_id: 5, identity_id: 9 },
    });
  });

  it("rejects a reauth response that is not confirmed", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(200, { data: {} })));
    await expect(
      reauthWithPassword("p", { action: "link_identity" }, "c"),
    ).rejects.toMatchObject({ code: "invalid_response" });
  });

  it("starts provider reauth, link and unlink against the contract paths", async () => {
    const start = json(200, {
      data: { authorization_url: "https://auth.example.test/a" },
    });
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(start.clone()));
    vi.stubGlobal("fetch", fetchMock);
    await startOidcReauth(
      "authelia",
      { action: "unlink_identity", target: { identity_id: 1 } },
      "c",
    );
    await startLinkIdentity("authelia", "c");
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/account/reauth/oidc/start",
      "/api/v2/account/identities/link",
    ]);
    expect(
      JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string),
    ).toEqual({
      provider: "authelia",
      action: "unlink_identity",
      target: { identity_id: 1 },
      client: "browser",
    });
    expect(
      JSON.parse((fetchMock.mock.calls[1][1] as RequestInit).body as string),
    ).toEqual({
      provider: "authelia",
      client: "browser",
    });

    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await unlinkIdentity(3, "c");
    await adminUnlinkIdentity(7, 3, "c");
    expect(
      fetchMock.mock.calls
        .slice(2)
        .map((call) => [call[0], (call[1] as RequestInit).method]),
    ).toEqual([
      ["/api/v2/account/identities/3", "DELETE"],
      ["/api/v2/admin/users/7/identities/3", "DELETE"],
    ]);
  });

  it("lists identities", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          json(200, { data: [identity], meta: { password_enabled: true } }),
        ),
    );
    expect((await listIdentities()).identities).toHaveLength(1);
  });
});

describe("error mapping", () => {
  it.each([
    [403, "reauth_required", "Confirm it is you to continue."],
    [
      409,
      "last_login_method",
      "This is your only way to sign in. Set up another sign-in method first.",
    ],
    [
      409,
      "identity_already_linked",
      "This login is already linked to a Stricknani account.",
    ],
    [
      400,
      "invalid_oidc_flow",
      "That sign-in attempt expired or could not be verified. Start again.",
    ],
    [
      503,
      "oidc_unavailable",
      "The login provider is unavailable right now. Try again later or use your password.",
    ],
    [404, "oidc_disabled", "This sign-in method is not available."],
    [
      403,
      "oidc_identity_unlinked",
      "No Stricknani account is linked to this login yet. Sign in with your password, then link it in your account settings.",
    ],
  ])("%s %s", async (status, code, message) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          json(status, { error: { code, message: "raw server text" } }),
        ),
    );
    const error = await unlinkIdentity(1, "c").catch(
      (failure: unknown) => failure,
    );
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe(message);
    expect((error as ApiError).code).toBe(code);
    expect(isReauthRequired(error)).toBe(code === "reauth_required");
  });

  it("keeps Retry-After for rate limits", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          json(
            429,
            { error: { code: "rate_limited", message: "x" } },
            { "retry-after": "30" },
          ),
        ),
    );
    const error = (await startLinkIdentity("authelia", "c").catch(
      (failure: unknown) => failure,
    )) as ApiError;
    expect(error.retryAfterSeconds).toBe(30);
  });

  it("does not treat a wrong reauth password as an expired session", async () => {
    const handler = vi.fn();
    advanceAuthEpoch();
    setUnauthorizedHandler(handler);
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          json(401, { error: { code: "invalid_credentials", message: "x" } }),
        ),
    );
    await expect(
      reauthWithPassword("wrong", { action: "link_identity" }, "c"),
    ).rejects.toMatchObject({ code: "invalid_credentials", status: 401 });
    expect(handler).not.toHaveBeenCalled();

    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          json(401, { error: { code: "session_expired", message: "x" } }),
        ),
    );
    await expect(unlinkIdentity(1, "c")).rejects.toMatchObject({
      code: "session_expired",
    });
    expect(handler).toHaveBeenCalledOnce();
  });
});
