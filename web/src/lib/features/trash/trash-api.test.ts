import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import {
  listTrash,
  parseTrashItem,
  parseTrashList,
  purgeTrashItem,
  restoredTarget,
  restoreTrashItem,
} from "./trash-api";
import { TRASH_KINDS, trashSegment } from "./trash-kinds";

const session = {
  user: {
    id: 2,
    email: "test@example.test",
    is_admin: false,
    avatar_url: null,
  },
  csrf_token: "csrf-fixture",
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
  },
  locale: "en" as const,
};

const project = {
  kind: "project",
  id: 7,
  name: "Socks",
  owner: { id: 2, email: "anna@example.test", name: "Anna", avatar_url: null },
  deleted_at: "2026-11-01T10:00:00Z",
  purge_at: "2027-01-30T10:00:00Z",
  days_remaining: 80,
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("trash item parsing", () => {
  it("reads a top-level item", () => {
    expect(parseTrashItem(project)).toMatchObject({
      kind: "project",
      id: 7,
      name: "Socks",
      owner: { id: 2, name: "Anna" },
      days_remaining: 80,
      parent: null,
      detail: null,
    });
  });

  it("reads a same-origin thumbnail and ignores anything else", () => {
    expect(
      parseTrashItem({
        ...project,
        thumbnail_url: "/media/thumbnails/projects/7/thumb_a.jpg",
      }).thumbnail_url,
    ).toBe("/media/thumbnails/projects/7/thumb_a.jpg");
    expect(
      parseTrashItem({
        ...project,
        thumbnail_url: "/api/v2/trash/images/3/thumbnail",
      }).thumbnail_url,
    ).toBe("/api/v2/trash/images/3/thumbnail");
    expect(parseTrashItem(project).thumbnail_url).toBeNull();
    for (const url of ["https://evil.test/a.jpg", "//evil.test/a.jpg", 5, ""]) {
      expect(
        parseTrashItem({ ...project, thumbnail_url: url }).thumbnail_url,
      ).toBeNull();
    }
  });

  it("reads parent and detail of a child item", () => {
    const item = parseTrashItem({
      ...project,
      kind: "step",
      id: 3,
      name: "Step 3",
      parent: { kind: "project", id: 7, name: "Socks" },
      detail: "Heel",
    });
    expect(item.parent).toEqual({ kind: "project", id: 7, name: "Socks" });
    expect(item.detail).toBe("Heel");
  });

  it("accepts every kind, with or without an owner", () => {
    for (const kind of TRASH_KINDS) {
      expect(parseTrashItem({ ...project, kind, owner: null }).kind).toBe(kind);
    }
  });

  it("drops a malformed parent instead of failing", () => {
    expect(
      parseTrashItem({ ...project, parent: { kind: "category", id: 1 } })
        .parent,
    ).toBeNull();
  });

  it.each([
    { ...project, kind: "folder" },
    { ...project, id: 0 },
    { ...project, name: 4 },
    { ...project, days_remaining: -1 },
    { ...project, days_remaining: 1.5 },
    { ...project, purge_at: undefined },
    null,
  ])("rejects an invalid item %#", (value) => {
    expect(() => parseTrashItem(value)).toThrow(ApiError);
  });

  it("reads the list meta and defaults it", () => {
    expect(
      parseTrashList({
        data: [project],
        meta: { scope: "all", retention_days: 30 },
      }),
    ).toMatchObject({ scope: "all", retention_days: 30 });
    expect(parseTrashList({ data: [] })).toEqual({
      items: [],
      scope: "mine",
      retention_days: 90,
    });
    expect(() => parseTrashList({})).toThrow(ApiError);
  });
});

describe("trash requests", () => {
  it("lists with the requested scope", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: [project],
        meta: { scope: "all", retention_days: 90 },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const list = await listTrash("all");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v2/trash?scope=all");
    expect(list.items).toHaveLength(1);
  });

  it("restores and purges by plural segment with CSRF", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    await restoreTrashItem("category", 4, session);
    await purgeTrashItem("attachment", 9, session);
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/trash/categories/4/restore",
      "/api/v2/trash/attachments/9",
    ]);
    expect(fetchMock.mock.calls.map((call) => call[1].method)).toEqual([
      "POST",
      "DELETE",
    ]);
    for (const call of fetchMock.mock.calls)
      expect(call[1].headers.get("x-csrf-token")).toBe("csrf-fixture");
  });

  it("maps a trashed parent to a translated error", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { error: { code: "parent_in_trash", message: "restore first" } },
            { status: 409 },
          ),
        ),
    );
    await expect(restoreTrashItem("step", 3, session)).rejects.toMatchObject({
      status: 409,
      code: "parent_in_trash",
      message:
        "Restore the project or yarn it belongs to from the trash first.",
    });
  });
});

describe("trash helpers", () => {
  it("uses the plural path segment of each kind", () => {
    expect(TRASH_KINDS.map(trashSegment)).toEqual([
      "projects",
      "yarns",
      "categories",
      "steps",
      "images",
      "attachments",
      "photos",
    ]);
  });

  it("points a restored item at its page or its parent", () => {
    expect(restoredTarget("project", 7, null)).toEqual({
      kind: "project",
      id: 7,
    });
    expect(
      restoredTarget("photo", 3, { kind: "yarn", id: 5, name: "Wool" }),
    ).toEqual({ kind: "yarn", id: 5 });
    expect(restoredTarget("category", 2, null)).toBeNull();
  });
});
