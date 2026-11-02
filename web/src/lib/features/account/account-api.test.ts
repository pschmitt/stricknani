import { afterEach, describe, expect, it, vi } from "vitest";
import {
  changePassword,
  createApiToken,
  deleteAvatar,
  getAccount,
  listApiTokens,
  revokeApiToken,
  uploadAvatar,
} from "./account-api";

const session = {
  user: {
    id: 17,
    email: "knitter@example.test",
    is_admin: false,
    avatar_url: null,
  },
  csrf_token: "account-csrf",
  capabilities: {
    signup: false,
    external_imports: false,
    ai: false,
    ocr: false,
    archive: false,
  },
  locale: "en" as const,
};

const account = {
  id: 17,
  email: "knitter@example.test",
  is_admin: false,
  avatar_url: "/media/thumbnails/users/17/avatar.jpg",
};

const token = {
  id: 41,
  name: "laptop",
  created_at: "2026-11-01T12:00:00.000000Z",
  expires_at: null,
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("account v2 API contract", () => {
  it("parses the flat profile and token-list DTOs", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ data: account }))
        .mockResolvedValueOnce(Response.json({ data: [token] })),
    );
    await expect(getAccount()).resolves.toEqual(account);
    await expect(listApiTokens()).resolves.toEqual([token]);
  });

  it("sends same-origin CSRF on create, revoke, password, and avatar operations", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json(
          {
            data: {
              id: token.id,
              name: token.name,
              created_at: token.created_at,
              token: "secret-once",
            },
          },
          { status: 201 },
        ),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(
        Response.json({
          data: { avatar_url: "/media/thumbnails/users/17/new.jpg" },
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createApiToken("laptop", session)).resolves.toEqual({
      ...token,
      token: "secret-once",
    });
    await revokeApiToken(41, session);
    await changePassword("current password", "new password", session);
    const file = new File(["image bytes"], "portrait.png", {
      type: "image/png",
    });
    await expect(uploadAvatar(file, session)).resolves.toBe(
      "/media/thumbnails/users/17/new.jpg",
    );
    await deleteAvatar(session);

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/account/api-tokens",
      "/api/v2/account/api-tokens/41",
      "/api/v2/account/password",
      "/api/v2/account/avatar",
      "/api/v2/account/avatar",
    ]);
    expect(
      fetchMock.mock.calls.map((call) => (call[1] as RequestInit).method),
    ).toEqual(["POST", "DELETE", "PUT", "POST", "DELETE"]);
    for (const call of fetchMock.mock.calls) {
      expect(
        new Headers((call[1] as RequestInit).headers).get("x-csrf-token"),
      ).toBe("account-csrf");
    }

    const createBody = JSON.parse(
      String((fetchMock.mock.calls[0]?.[1] as RequestInit).body),
    );
    expect(createBody).toEqual({ name: "laptop" });
    const passwordBody = JSON.parse(
      String((fetchMock.mock.calls[2]?.[1] as RequestInit).body),
    );
    expect(passwordBody).toEqual({
      current_password: "current password",
      new_password: "new password",
    });
    const avatarForm = (fetchMock.mock.calls[3]?.[1] as RequestInit)
      .body as FormData;
    const uploadedFile = avatarForm.get("file");
    expect(uploadedFile).toBeInstanceOf(File);
    expect((uploadedFile as File).name).toBe(file.name);
    expect((uploadedFile as File).type).toBe(file.type);
    expect((uploadedFile as File).size).toBe(file.size);
  });

  it("preserves server field errors and rejects malformed DTOs", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          Response.json(
            {
              error: {
                code: "validation_error",
                message: "Account fields are invalid",
                fields: { name: "too long" },
              },
            },
            { status: 422 },
          ),
        )
        .mockResolvedValueOnce(
          Response.json({ data: { ...account, id: "17" } }),
        ),
    );
    await expect(
      createApiToken("x".repeat(256), session),
    ).rejects.toMatchObject({
      status: 422,
      code: "validation_error",
      fields: { name: "too long" },
    });
    await expect(getAccount()).rejects.toMatchObject({
      code: "invalid_response",
    });
  });
});
