import { afterEach, describe, expect, it, vi } from "vitest";
import {
  apiRequest,
  ApiError,
  getSession,
  login,
  logout,
  setUnauthorizedHandler,
} from "./api";

vi.mock("./i18n.svelte", () => ({ t: (message: string) => message }));

const sessionFixture = {
  data: {
    user: { id: 42, email: "knitter@example.test", is_admin: false },
    csrf_token: "csrf-fixture",
    capabilities: {
      signup: true,
      external_imports: false,
      ai: false,
      ocr: false,
      archive: true,
    },
    locale: "en",
  },
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  setUnauthorizedHandler(() => {});
});

describe("same-origin API client", () => {
  it("validates the documented session envelope and numeric user ID", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(Response.json(sessionFixture)),
    );
    await expect(getSession()).resolves.toMatchObject({ user: { id: 42 } });
  });

  it.each([
    null,
    [],
    {},
    { data: null },
    {
      data: {
        ...sessionFixture.data,
        user: { ...sessionFixture.data.user, id: "42" },
      },
    },
  ])("rejects malformed session JSON (%j)", async (payload) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(payload)));
    await expect(getSession()).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("accepts the empty 204 logout response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    await expect(logout("csrf-fixture")).resolves.toBeUndefined();
  });

  it("sends JSON and the CSRF header for login with same-origin credentials", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(sessionFixture));
    vi.stubGlobal("fetch", fetchMock);
    await login("knitter@example.test", "example-password", "csrf-fixture");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v2/auth/login");
    expect(init.credentials).toBe("same-origin");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("csrf-fixture");
    expect(JSON.parse(String(init.body))).toEqual({
      email: "knitter@example.test",
      password: "example-password",
    });
  });

  it("passes AbortSignal through to fetch", async () => {
    const controller = new AbortController();
    const fetchMock = vi.fn().mockResolvedValue(Response.json(sessionFixture));
    vi.stubGlobal("fetch", fetchMock);
    await getSession(controller.signal);
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).signal).toBe(
      controller.signal,
    );
  });

  it("notifies the session handler for an API 401, but not for a bad login", async () => {
    const unauthorized = vi.fn();
    setUnauthorizedHandler(unauthorized);
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { error: { code: "unauthorized", message: "expired" } },
            { status: 401 },
          ),
        ),
    );
    await expect(apiRequest("/api/v2/projects")).rejects.toMatchObject({
      status: 401,
    });
    expect(unauthorized).toHaveBeenCalledOnce();
    await expect(
      login("a@example.test", "bad", "csrf-fixture"),
    ).rejects.toMatchObject({ status: 401 });
    expect(unauthorized).toHaveBeenCalledOnce();
  });

  it("does not set multipart content-type for FormData", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(Response.json({ data: { ok: true } }));
    vi.stubGlobal("fetch", fetchMock);
    const body = new FormData();
    body.set("file", new Blob(["yarn"]), "sample.txt");
    await apiRequest("/api/v2/imports", {
      method: "POST",
      body,
      csrfToken: "csrf-fixture",
    });
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(new Headers(init.headers).has("content-type")).toBe(false);
    expect(init.body).toBe(body);
  });

  it("maps API error codes to localized app copy rather than server English text", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            error: {
              code: "invalid_credentials",
              message: "English backend string",
            },
          },
          { status: 401 },
        ),
      ),
    );
    const error = await apiRequest("/api/v2/auth/login", {
      method: "POST",
      body: {},
    }).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe(
      "The email or password is incorrect.",
    );
  });
});
