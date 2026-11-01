import { afterEach, describe, expect, it, vi } from "vitest";
import { createAccount } from "./signup-api";

vi.mock("#lib/i18n.svelte.ts", () => ({ t: (message: string) => message }));

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("signup API", () => {
  it("sends only email and password with same-origin CSRF protection", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { data: { id: 42, email: "new@example.test", is_admin: false } },
          { status: 201 },
        ),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      createAccount("new@example.test", "secret-password", "csrf-fixture"),
    ).resolves.toEqual({
      id: 42,
      email: "new@example.test",
      is_admin: false,
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v2/auth/signup");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("same-origin");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("csrf-fixture");
    expect(JSON.parse(String(init.body))).toEqual({
      email: "new@example.test",
      password: "secret-password",
    });
  });

  it("forwards cancellation and rejects malformed or privileged DTOs", async () => {
    const controller = new AbortController();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json(
          { data: { id: 42, email: "new@example.test", is_admin: false } },
          { status: 201 },
        ),
      )
      .mockResolvedValueOnce(
        Response.json(
          { data: { id: "42", email: "new@example.test", is_admin: true } },
          { status: 201 },
        ),
      );
    vi.stubGlobal("fetch", fetchMock);

    await createAccount(
      "new@example.test",
      "secret-password",
      "csrf-fixture",
      controller.signal,
    );
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).signal).toBe(
      controller.signal,
    );
    await expect(
      createAccount("new@example.test", "secret-password", "csrf-fixture"),
    ).rejects.toMatchObject({ code: "invalid_response" });
  });
});
