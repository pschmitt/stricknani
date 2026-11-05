import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import { linkedYarnsLabel, moveYarnsLabel } from "./share-copy";
import {
  addShare,
  changeShareRole,
  leaveShare,
  listShares,
  listYarnCandidates,
  parsePerson,
  peoplePath,
  personAsOwner,
  revokeShare,
  searchPeople,
  transferOwnership,
} from "./sharing-api";

const anna = { id: 12, name: "Anna", avatar_url: "/api/v2/people/12/avatar/a" };
const share = {
  user: anna,
  role: "viewer",
  created_at: "2026-11-01T10:00:00Z",
  updated_at: "2026-11-01T10:00:00Z",
};
const session = {
  user: { id: 1, email: "o@example.test", is_admin: false, avatar_url: null },
  csrf_token: "csrf-fixture",
  capabilities: {},
  locale: "en" as const,
} as never;

function stub(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function lastRequest(fetchMock: ReturnType<typeof vi.fn>) {
  const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  return {
    path,
    method: init.method,
    body: init.body === undefined ? undefined : JSON.parse(String(init.body)),
    csrf: new Headers(init.headers).get("x-csrf-token"),
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("sharing API contract", () => {
  it("parses people without ever carrying an email", () => {
    expect(parsePerson({ ...anna, email: "anna@example.test" })).toEqual(anna);
    expect(
      parsePerson({ ...anna, avatar_url: "https://evil.test/a" }).avatar_url,
    ).toBeNull();
    expect(() => parsePerson({ id: 0, name: "x" })).toThrow(ApiError);
    expect(personAsOwner(anna)).toEqual({ ...anna, email: "" });
  });

  it("lists the owner and the shares", async () => {
    const fetchMock = stub(
      Response.json({
        data: {
          owner: { id: 1, name: "Olga", avatar_url: null },
          shares: [share],
        },
      }),
    );
    const result = await listShares("projects", 5);
    expect(lastRequest(fetchMock).path).toBe("/api/v2/projects/5/shares");
    expect(result.owner.name).toBe("Olga");
    expect(result.shares[0]?.role).toBe("viewer");
  });

  it("adds, changes and revokes through the right verbs with the CSRF token", async () => {
    let fetchMock = stub(Response.json({ data: share }, { status: 201 }));
    await addShare("yarns", 5, 12, "viewer", session);
    expect(lastRequest(fetchMock)).toMatchObject({
      path: "/api/v2/yarns/5/shares",
      method: "POST",
      body: { user_id: 12, role: "viewer" },
      csrf: "csrf-fixture",
    });

    fetchMock = stub(Response.json({ data: { ...share, role: "editor" } }));
    const changed = await changeShareRole("projects", 5, 12, "editor", session);
    expect(changed.role).toBe("editor");
    expect(lastRequest(fetchMock)).toMatchObject({
      path: "/api/v2/projects/5/shares/12",
      method: "PATCH",
      body: { role: "editor" },
    });

    fetchMock = stub(new Response(null, { status: 204 }));
    await revokeShare("projects", 5, 12, session);
    expect(lastRequest(fetchMock)).toMatchObject({
      path: "/api/v2/projects/5/shares/12",
      method: "DELETE",
    });

    fetchMock = stub(new Response(null, { status: 204 }));
    await leaveShare("yarns", 5, session);
    expect(lastRequest(fetchMock)).toMatchObject({
      path: "/api/v2/yarns/5/leave",
      method: "POST",
    });
  });

  it("surfaces a duplicate share as a conflict", async () => {
    stub(
      Response.json(
        { error: { code: "conflict", message: "Already shared" } },
        { status: 409 },
      ),
    );
    await expect(
      addShare("projects", 5, 12, "viewer", session),
    ).rejects.toMatchObject({
      status: 409,
      code: "conflict",
    });
  });

  it("sends move_linked_yarns for projects only", async () => {
    const result = {
      data: {
        new_owner: anna,
        access: {
          role: "editor",
          derived: false,
          can_edit: true,
          can_manage: false,
        },
        moved_yarn_ids: [3, 5],
      },
    };
    let fetchMock = stub(Response.json(result));
    const moved = await transferOwnership("projects", 5, 12, true, session);
    expect(moved.moved_yarn_ids).toEqual([3, 5]);
    expect(moved.access.role).toBe("editor");
    expect(lastRequest(fetchMock).body).toEqual({
      new_owner_id: 12,
      move_linked_yarns: true,
    });

    fetchMock = stub(Response.json(result));
    await transferOwnership("yarns", 9, 12, true, session);
    expect(lastRequest(fetchMock).body).toEqual({ new_owner_id: 12 });
  });

  it("builds bounded people search URLs", async () => {
    expect(peoplePath("", 1)).toBe("/api/v2/people");
    expect(peoplePath("  an ", 2)).toBe("/api/v2/people?q=an&page=2");
    expect(peoplePath("x".repeat(100), 1)).toBe(
      `/api/v2/people?q=${"x".repeat(64)}`,
    );
    const fetchMock = stub(
      Response.json({
        data: { items: [anna], page: 1, per_page: 20, has_more: true },
      }),
    );
    const page = await searchPeople("an", 1);
    expect(lastRequest(fetchMock).path).toBe("/api/v2/people?q=an");
    expect(page.has_more).toBe(true);
  });

  it("reads the owner's yarn candidates for an editor", async () => {
    const fetchMock = stub(
      Response.json({
        data: {
          items: [
            {
              id: 3,
              name: "Sky",
              brand: null,
              colorway: null,
              weight_category: "DK",
              linked: false,
            },
          ],
          page: 1,
          per_page: 50,
          has_more: false,
        },
      }),
    );
    const page = await listYarnCandidates(8, " sky ", 1);
    expect(lastRequest(fetchMock).path).toBe(
      "/api/v2/projects/8/yarn-candidates?q=sky",
    );
    expect(page.items[0]?.name).toBe("Sky");
  });

  it("rejects malformed responses", async () => {
    stub(
      Response.json({
        data: { owner: anna, shares: [{ user: anna, role: "admin" }] },
      }),
    );
    await expect(listShares("projects", 5)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("words the yarn counts in singular and plural", () => {
    expect(linkedYarnsLabel(1, "en")).toBe("1 linked yarn");
    expect(linkedYarnsLabel(3, "en")).toBe("3 linked yarns");
    expect(moveYarnsLabel(1, "en")).toBe("Also move 1 linked yarn");
    expect(moveYarnsLabel(2, "en")).toBe("Also move 2 linked yarns");
  });
});
