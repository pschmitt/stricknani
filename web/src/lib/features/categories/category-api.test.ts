import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createCategory,
  deleteCategory,
  listCategories,
  renameCategory,
} from "./category-api";

const session = {
  user: { id: 2, email: "test@example.test", is_admin: false },
  csrf_token: "csrf-fixture",
  capabilities: {
    signup: false,
    external_imports: false,
    ai: false,
    ocr: false,
    archive: false,
  },
  locale: "en" as const,
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("category browser API contract", () => {
  it("reads the data envelope and validates numeric IDs", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(Response.json({ data: [{ id: 2, name: "Socks" }] })),
    );
    await expect(listCategories()).resolves.toEqual([{ id: 2, name: "Socks" }]);
  });

  it("sends same-origin CSRF for create, rename, and delete", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ data: { id: 2, name: "Socks" } }))
      .mockResolvedValueOnce(Response.json({ data: { id: 2, name: "Hats" } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    await createCategory("Socks", session);
    await renameCategory(2, "Hats", session);
    await deleteCategory(2, session);

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/categories",
      "/api/v2/categories/2",
      "/api/v2/categories/2",
    ]);
    expect(
      fetchMock.mock.calls.map((call) => (call[1] as RequestInit).method),
    ).toEqual(["POST", "PUT", "DELETE"]);
    for (const call of fetchMock.mock.calls) {
      expect(
        new Headers((call[1] as RequestInit).headers).get("x-csrf-token"),
      ).toBe("csrf-fixture");
    }
  });

  it("preserves server field errors for duplicate category names", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            error: {
              code: "conflict",
              message: "Another category already uses that name",
              fields: { name: "Name already exists" },
            },
          },
          { status: 409 },
        ),
      ),
    );
    await expect(renameCategory(2, "Hats", session)).rejects.toMatchObject({
      status: 409,
      code: "conflict",
      fields: { name: "Name already exists" },
    });
  });
});
