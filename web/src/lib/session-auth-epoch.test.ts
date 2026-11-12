import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SessionData } from "./api";
import { apiRequest } from "./api";
import { session, signIn } from "./session.svelte";

const oldAccount: SessionData = {
  user: {
    id: 61,
    email: "old@example.test",
    is_admin: false,
    avatar_url: null,
  },
  csrf_token: "old-csrf",
  capabilities: {
    signup: false,
    external_imports: false,
    ai: false,
    ocr: false,
    archive: false,
    media_upload: false,
    media_delete: false,
    import_preview: false,
    archive_state: false,
    account_actions: true,
    admin_actions: false,
    passkeys: false,
  },
  locale: "en",
};

const newAccount: SessionData = {
  ...oldAccount,
  user: {
    id: 62,
    email: "new@example.test",
    is_admin: false,
    avatar_url: null,
  },
  csrf_token: "new-csrf",
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("authentication epoch", () => {
  beforeEach(() => {
    session.status = "ready";
    session.data = oldAccount;
    session.error = null;
    session.expired = false;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("ignores an old request 401 after a successful sign-in as another user", async () => {
    const oldResponse = deferred<Response>();
    const inFlightLogin = deferred<Response>();
    const inFlightRequest = deferred<Response>();
    const fetchMock = vi
      .fn()
      .mockReturnValueOnce(oldResponse.promise)
      .mockReturnValueOnce(inFlightLogin.promise)
      .mockReturnValueOnce(inFlightRequest.promise);
    vi.stubGlobal("fetch", fetchMock);

    const oldRequest = apiRequest("/api/v2/projects");
    const signInRequest = signIn("new@example.test", "new-password");
    const requestDuringSignIn = apiRequest("/api/v2/projects");
    inFlightLogin.resolve(Response.json({ data: newAccount }));
    await signInRequest;
    oldResponse.resolve(
      Response.json(
        { error: { code: "unauthorized", message: "expired" } },
        { status: 401 },
      ),
    );
    inFlightRequest.resolve(
      Response.json(
        { error: { code: "unauthorized", message: "expired" } },
        { status: 401 },
      ),
    );
    await expect(
      Promise.all([oldRequest, requestDuringSignIn]),
    ).rejects.toMatchObject({
      status: 401,
    });

    expect(session.data).toEqual(newAccount);
    expect(session.expired).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
});
