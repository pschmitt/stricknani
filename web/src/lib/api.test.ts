import { afterEach, describe, expect, it, vi } from "vitest";
import {
  apiRequest,
  ApiError,
  advanceAuthEpoch,
  getSession,
  hasPendingApiMutations,
  login,
  logout,
  setUnauthorizedHandler,
} from "./api";

vi.mock("./i18n.svelte", () => ({ t: (message: string) => message }));

const sessionFixture = {
  data: {
    user: {
      id: 42,
      email: "knitter@example.test",
      is_admin: false,
      avatar_url: null,
    },
    csrf_token: "csrf-fixture",
    capabilities: {
      signup: true,
      external_imports: false,
      ai: false,
      ocr: false,
      archive: true,
      media_upload: false,
      media_delete: false,
      import_preview: false,
      archive_state: true,
      account_actions: true,
      admin_actions: false,
    },
    locale: "en",
  },
};

const actionCapabilities = [
  "media_upload",
  "media_delete",
  "import_preview",
  "archive_state",
  "account_actions",
  "admin_actions",
] as const;

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
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

  it("accepts the optional impersonation block and rejects a malformed one", async () => {
    const impersonation = {
      user: { id: 7, email: "member@example.invalid", name: "Member" },
      admin: { id: 4, email: "admin@example.invalid" },
      expires_at: 1_900_000_000,
    };
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          Response.json({ data: { ...sessionFixture.data, impersonation } }),
        )
        .mockResolvedValueOnce(
          Response.json({
            data: {
              ...sessionFixture.data,
              impersonation: { ...impersonation, expires_at: "soon" },
            },
          }),
        ),
    );
    await expect(getSession()).resolves.toMatchObject({ impersonation });
    await expect(getSession()).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it.each(actionCapabilities)(
    "requires the %s capability in a session response",
    async (capability) => {
      const capabilities: Record<string, unknown> = {
        ...sessionFixture.data.capabilities,
      };
      delete capabilities[capability];
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          Response.json({
            data: { ...sessionFixture.data, capabilities },
          }),
        ),
      );

      await expect(getSession()).rejects.toMatchObject({
        code: "invalid_response",
      });
    },
  );

  it.each(actionCapabilities)(
    "requires %s to be a boolean in a session response",
    async (capability) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          Response.json({
            data: {
              ...sessionFixture.data,
              capabilities: {
                ...sessionFixture.data.capabilities,
                [capability]: "enabled",
              },
            },
          }),
        ),
      );

      await expect(getSession()).rejects.toMatchObject({
        code: "invalid_response",
      });
    },
  );

  it("accepts a same-user relative avatar thumbnail URL", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: {
            ...sessionFixture.data,
            user: {
              ...sessionFixture.data.user,
              avatar_url:
                "/media/thumbnails/users/42/thumb_0123456789abcdef0123456789abcdef.jpg",
            },
          },
        }),
      ),
    );

    await expect(getSession()).resolves.toMatchObject({
      user: {
        avatar_url:
          "/media/thumbnails/users/42/thumb_0123456789abcdef0123456789abcdef.jpg",
      },
    });
  });

  it("accepts the same-user Gravatar route and its source", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: {
            ...sessionFixture.data,
            user: {
              ...sessionFixture.data.user,
              avatar_url: "/media/thumbnails/users/42/gravatar",
              avatar_source: "gravatar",
            },
          },
        }),
      ),
    );

    await expect(getSession()).resolves.toMatchObject({
      user: {
        avatar_url: "/media/thumbnails/users/42/gravatar",
        avatar_source: "gravatar",
      },
    });
  });

  it.each([
    "/media/thumbnails/users/43/gravatar",
    "/media/thumbnails/users/42/gravatar/extra",
  ])("rejects another user's Gravatar route (%s)", async (avatarUrl) => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: {
            ...sessionFixture.data,
            user: { ...sessionFixture.data.user, avatar_url: avatarUrl },
          },
        }),
      ),
    );

    await expect(getSession()).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("rejects an unknown avatar source", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: {
            ...sessionFixture.data,
            user: { ...sessionFixture.data.user, avatar_source: "facebook" },
          },
        }),
      ),
    );

    await expect(getSession()).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it.each([
    "https://images.example.test/avatar.jpg",
    "/media/thumbnails/users/43/thumb_0123456789abcdef0123456789abcdef.jpg",
    "/media/thumbnails/users/42/../other.jpg",
  ])("rejects an unsafe session avatar URL (%s)", async (avatarUrl) => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: {
            ...sessionFixture.data,
            user: { ...sessionFixture.data.user, avatar_url: avatarUrl },
          },
        }),
      ),
    );

    await expect(getSession()).rejects.toMatchObject({
      code: "invalid_response",
    });
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

  it("tracks mutation requests until their response body settles", async () => {
    let resolveResponse!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            resolveResponse = resolve;
          }),
      ),
    );

    const request = apiRequest("/api/v2/projects", {
      method: "POST",
      body: {},
    });
    expect(hasPendingApiMutations()).toBe(true);
    resolveResponse(Response.json({ data: { id: 1 } }));
    await expect(request).resolves.toEqual({ data: { id: 1 } });
    expect(hasPendingApiMutations()).toBe(false);
  });

  it("releases the mutation counter after a failed request", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { error: { code: "conflict", message: "changed" } },
            { status: 409 },
          ),
        ),
    );

    await expect(
      apiRequest("/api/v2/projects", { method: "PUT", body: {} }),
    ).rejects.toMatchObject({ status: 409 });
    expect(hasPendingApiMutations()).toBe(false);
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

  it("does not let an obsolete or aborted 401 expire the current session", async () => {
    const unauthorized = vi.fn();
    setUnauthorizedHandler(unauthorized);
    let resolveResponse!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((resolve) => {
            resolveResponse = resolve;
          }),
      ),
    );

    const obsoleteRequest = apiRequest("/api/v2/projects");
    advanceAuthEpoch();
    resolveResponse(
      Response.json(
        { error: { code: "unauthorized", message: "expired" } },
        { status: 401 },
      ),
    );
    await expect(obsoleteRequest).rejects.toMatchObject({ status: 401 });
    expect(unauthorized).not.toHaveBeenCalled();

    const controller = new AbortController();
    const abortedRequest = apiRequest("/api/v2/projects", {
      signal: controller.signal,
    });
    controller.abort();
    resolveResponse(
      Response.json(
        { error: { code: "unauthorized", message: "expired" } },
        { status: 401 },
      ),
    );
    await expect(abortedRequest).rejects.toMatchObject({ status: 401 });
    expect(unauthorized).not.toHaveBeenCalled();
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

  it("parses delta Retry-After and never retries a mutation automatically", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { error: { code: "rate_limited", message: "try later" } },
          { status: 429, headers: { "Retry-After": "17" } },
        ),
      );
    vi.stubGlobal("fetch", fetchMock);

    const error = await apiRequest("/api/v2/projects", {
      method: "POST",
      body: { name: "Sample" },
    }).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ retryAfterSeconds: 17 });
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("parses an HTTP-date Retry-After into bounded seconds", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          { error: { code: "rate_limited", message: "try later" } },
          {
            status: 429,
            headers: { "Retry-After": "Thu, 01 Jan 2026 00:00:30 GMT" },
          },
        ),
      ),
    );

    const error = await apiRequest("/api/v2/projects").catch(
      (cause: unknown) => cause,
    );

    expect(error).toMatchObject({ retryAfterSeconds: 30 });
  });

  it.each([
    "-1",
    "1.5",
    "not a date",
    "Wed, 01 Jan 2026 00:00:30 GMT",
    "Wed, 31 Dec 2025 23:59:59 GMT",
  ])("ignores invalid or negative Retry-After values (%s)", async (header) => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { error: { code: "rate_limited", message: "try later" } },
            { status: 429, headers: { "Retry-After": header } },
          ),
        ),
    );

    const error = await apiRequest("/api/v2/projects").catch(
      (cause: unknown) => cause,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).retryAfterSeconds).toBeUndefined();
  });

  it("caps excessive Retry-After values", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { error: { code: "rate_limited", message: "try later" } },
            { status: 429, headers: { "Retry-After": "999999" } },
          ),
        ),
    );

    const error = await apiRequest("/api/v2/projects").catch(
      (cause: unknown) => cause,
    );

    expect(error).toMatchObject({ retryAfterSeconds: 86_400 });
  });

  it("retains a valid Retry-After on malformed error JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("not json", {
          status: 503,
          headers: { "Retry-After": "9" },
        }),
      ),
    );

    const error = await apiRequest("/api/v2/projects").catch(
      (cause: unknown) => cause,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 503,
      code: "invalid_response",
      retryAfterSeconds: 9,
    });
  });

  it.each([
    [
      "csrf_failed",
      "Your session check failed. Refresh the page and try again.",
    ],
    ["session_expired", "Your session expired. Sign in again."],
    [
      "conflict",
      "This item changed in another window. Your unsaved changes are still here.",
    ],
    ["payload_too_large", "This upload is larger than the allowed size."],
  ])("localizes the %s response", async (code, expectedMessage) => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            error: {
              code,
              message: "English backend message",
              ...(code === "conflict" ? { current: { id: 42 } } : {}),
            },
          },
          { status: code === "conflict" ? 409 : 403 },
        ),
      ),
    );

    const error = await apiRequest("/api/v2/projects/42", {
      method: "PUT",
      body: {},
    }).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ message: expectedMessage });
    if (code === "conflict")
      expect(error).toMatchObject({ current: { id: 42 } });
  });

  it.each([
    ["invalid_json", "The request could not be read. Refresh and try again."],
    ["feature_disabled", "This feature is not enabled for this account."],
    [
      "invalid_multipart",
      "The uploaded form could not be read. Choose the file again.",
    ],
    ["media_unavailable", "Media storage is unavailable. Try again later."],
    ["invalid_image", "This is not a valid image. Choose a different file."],
    [
      "ocr_unavailable",
      "Text recognition is unavailable right now. You can enter the text manually.",
    ],
    [
      "ocr_timeout",
      "Text recognition took too long. Try again or enter the text manually.",
    ],
    [
      "ocr_failed",
      "Text recognition could not complete. You can enter the text manually.",
    ],
    ["invalid_language", "Choose a supported language for text recognition."],
    ["invalid_src", "This image is not available for text recognition."],
    [
      "import_unavailable",
      "Import preview is unavailable right now. Try again later.",
    ],
    ["import_timeout", "The source took too long to import. Try again later."],
    [
      "import_fetch_failed",
      "The source could not be downloaded. Check the link and try again.",
    ],
    ["unsafe_url", "This link cannot be imported for security reasons."],
    [
      "unsupported_document",
      "This document type is not supported. Try a web page, text, Markdown, PDF, or image.",
    ],
    ["invalid_document", "This document could not be read. Try another file."],
    ["archive_unavailable", "Archiving is unavailable right now."],
  ])("maps %s to helpful app copy", async (code, expectedMessage) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { error: { code, message: "English backend message" } },
            { status: 503 },
          ),
        ),
    );

    const error = await apiRequest("/api/v2/resource").catch(
      (cause: unknown) => cause,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ message: expectedMessage });
  });
});
