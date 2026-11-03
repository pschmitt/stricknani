import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  advanceAuthEpoch: vi.fn(),
  getSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  setUnauthorizedHandler: vi.fn(),
}));

vi.mock("./api", () => ({
  advanceAuthEpoch: api.advanceAuthEpoch,
  ApiError: class ApiError extends Error {
    constructor(message: string) {
      super(message);
    }
  },
  getSession: api.getSession,
  login: api.login,
  logout: api.logout,
  setUnauthorizedHandler: api.setUnauthorizedHandler,
}));

vi.mock("./i18n.svelte", () => ({ t: (message: string) => message }));

import type { SessionData } from "./api";
import {
  patchSessionAvatar,
  refreshSession,
  session,
  signIn,
  signOut,
} from "./session.svelte";

const anonymous: SessionData = {
  user: null,
  csrf_token: "bootstrap-token",
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
    account_actions: false,
    admin_actions: false,
  },
  locale: "en",
};

const authenticated: SessionData = {
  ...anonymous,
  user: {
    id: 73,
    email: "synthetic@example.test",
    is_admin: false,
    avatar_url: null,
  },
  csrf_token: "authenticated-token",
  capabilities: { ...anonymous.capabilities, account_actions: true },
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("session request ordering", () => {
  beforeEach(() => {
    api.getSession.mockReset();
    api.login.mockReset();
    api.logout.mockReset();
    api.setUnauthorizedHandler.mockClear();
    api.advanceAuthEpoch.mockClear();
    session.status = "ready";
    session.data = anonymous;
    session.error = null;
    session.expired = false;
  });

  it("does not let an older bootstrap overwrite a successful sign-in", async () => {
    const oldBootstrap = deferred<SessionData>();
    api.getSession.mockReturnValueOnce(oldBootstrap.promise);
    api.login.mockResolvedValueOnce(authenticated);

    const bootstrap = refreshSession();
    await signIn("synthetic@example.test", "synthetic-password");
    oldBootstrap.resolve(anonymous);
    await bootstrap;

    expect(session.data).toEqual(authenticated);
    expect(session.status).toBe("ready");
    expect(session.expired).toBe(false);
  });

  it("does not let a pending bootstrap restore the account after sign-out", async () => {
    const oldBootstrap = deferred<SessionData>();
    session.data = authenticated;
    api.getSession
      .mockReturnValueOnce(oldBootstrap.promise)
      .mockResolvedValueOnce(anonymous);
    api.logout.mockResolvedValueOnce(undefined);

    const bootstrap = refreshSession();
    await signOut();
    oldBootstrap.resolve(authenticated);
    await bootstrap;

    expect(session.data).toEqual(anonymous);
    expect(session.status).toBe("ready");
  });

  it("patches the avatar without reloading or changing session status", () => {
    session.data = authenticated;
    const expectedSession = session.data;

    expect(
      patchSessionAvatar(
        expectedSession,
        "/media/thumbnails/users/73/thumb_0123456789abcdef0123456789abcdef.jpg",
      ),
    ).toBe(true);
    expect(session.status).toBe("ready");
    expect(session.data?.user?.avatar_url).toBe(
      "/media/thumbnails/users/73/thumb_0123456789abcdef0123456789abcdef.jpg",
    );
  });

  it("records the avatar source, defaulting uploads", () => {
    session.data = authenticated;
    const expectedSession = session.data;
    expect(
      patchSessionAvatar(
        expectedSession,
        "/media/thumbnails/users/73/gravatar",
        "gravatar",
      ),
    ).toBe(true);
    expect(session.data?.user?.avatar_source).toBe("gravatar");
    const next = session.data;
    expect(patchSessionAvatar(next, null)).toBe(true);
    expect(session.data?.user?.avatar_source).toBeNull();
  });

  it("ignores avatar mutations that complete after session replacement", () => {
    session.data = authenticated;
    const expectedSession = session.data;
    session.data = {
      ...authenticated,
      user: { ...authenticated.user!, id: 74 },
    };

    expect(
      patchSessionAvatar(
        expectedSession,
        "/media/thumbnails/users/73/thumb_0123456789abcdef0123456789abcdef.jpg",
      ),
    ).toBe(false);
    expect(session.data.user?.id).toBe(74);
    expect(session.data.user?.avatar_url).toBeNull();
  });
});
