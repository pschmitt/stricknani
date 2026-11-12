import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import {
  auditFiltersFromSearch,
  auditSearchFromFilters,
  createAdminUser,
  getAuditEventDetail,
  impersonateUser,
  listAdminUsers,
  listAuditEvents,
  resetUserPassword,
  parseImpersonator,
  setUserActive,
  stopImpersonation,
  type AdminUser,
} from "./admin-api";

const session = {
  user: {
    id: 4,
    email: "admin@example.invalid",
    is_admin: true,
    avatar_url: null,
  },
  csrf_token: "csrf-test",
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
    admin_actions: true,
    passkeys: false,
  },
  locale: "en" as const,
};

const user: AdminUser = {
  id: 7,
  email: "member@example.invalid",
  active: true,
  is_admin: false,
  avatar_url: null,
};

const projectResource = (
  id: number,
  name: string | null = "Sample project",
) => ({
  kind: "project",
  id,
  name,
  name_source: name === null ? "unavailable" : "current",
  href: `/app/admin/resources/project/${id}`,
  preview_url: null,
  exists: true,
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("administrator API", () => {
  it("lists users with validated pagination", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: [user],
        meta: { page: 2, per_page: 50, has_more: true },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(listAdminUsers(2)).resolves.toEqual({
      data: [user],
      meta: { page: 2, per_page: 50, has_more: true },
    });
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v2/admin/users?page=2");
  });

  it("parses the optional linked identity count and rejects bad values", async () => {
    const listing = (value: unknown) =>
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          Response.json({
            data: [{ ...user, linked_identities: value }],
            meta: { page: 1, per_page: 50, has_more: false },
          }),
        ),
      );
    listing(2);
    await expect(listAdminUsers(1)).resolves.toMatchObject({
      data: [{ linked_identities: 2 }],
    });
    listing(-1);
    await expect(listAdminUsers(1)).rejects.toMatchObject({
      code: "invalid_response",
    });
    listing("1");
    await expect(listAdminUsers(1)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("accepts only same-origin admin avatar URLs and binds users to their own", async () => {
    const withAvatar = {
      ...user,
      avatar_url: "/api/v2/admin/users/7/avatar/thumb_profile.jpg",
    };
    const event = {
      id: 9,
      actor_email: "member@example.invalid",
      actor_avatar_url: "/api/v2/admin/users/7/avatar/thumb_profile.jpg",
      legacy_actor_user_id: null,
      entity_type: "project",
      entity_id: 3,
      action: "created",
      created_at: "2026-11-01T12:00:00Z",
      resource: projectResource(3),
      has_changes: true,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          data: [withAvatar],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [event],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [
            { ...withAvatar, avatar_url: "https://evil.invalid/avatar.jpg" },
          ],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [
            {
              ...event,
              actor_avatar_url: "https://evil.invalid/avatar.jpg",
            },
          ],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(listAdminUsers(1)).resolves.toMatchObject({
      data: [withAvatar],
    });
    await expect(
      listAuditEvents({ page: 1, entity: "all", action: "" }),
    ).resolves.toMatchObject({ data: [event] });
    await expect(listAdminUsers(1)).rejects.toMatchObject({
      code: "invalid_response",
    });
    await expect(
      listAuditEvents({ page: 1, entity: "all", action: "" }),
    ).rejects.toMatchObject({ code: "invalid_response" });
  });

  it("accepts the server-fetched Gravatar route for users and audit actors", async () => {
    const gravatar = "/api/v2/admin/users/7/avatar/gravatar";
    const event = {
      id: 9,
      actor_email: "member@example.invalid",
      actor_avatar_url: gravatar,
      legacy_actor_user_id: null,
      entity_type: "project",
      entity_id: 3,
      action: "created",
      created_at: "2026-11-01T12:00:00Z",
      resource: projectResource(3),
      has_changes: true,
    };
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          Response.json({
            data: [{ ...user, avatar_url: gravatar }],
            meta: { page: 1, per_page: 50, has_more: false },
          }),
        )
        .mockResolvedValueOnce(
          Response.json({
            data: [event],
            meta: { page: 1, per_page: 50, has_more: false },
          }),
        )
        .mockResolvedValueOnce(
          Response.json({
            data: [
              { ...user, avatar_url: "/api/v2/admin/users/8/avatar/other" },
            ],
            meta: { page: 1, per_page: 50, has_more: false },
          }),
        ),
    );
    await expect(listAdminUsers(1)).resolves.toMatchObject({
      data: [{ avatar_url: gravatar }],
    });
    await expect(
      listAuditEvents({ page: 1, entity: "all", action: "" }),
    ).resolves.toMatchObject({ data: [event] });
    await expect(listAdminUsers(1)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("binds audit preview URLs to a live resource and media namespace", async () => {
    const event = {
      id: 5,
      actor_email: null,
      actor_avatar_url: null,
      legacy_actor_user_id: null,
      entity_type: "project",
      entity_id: 3,
      action: "updated",
      created_at: "2026-11-01T12:00:00Z",
      resource: {
        ...projectResource(3),
        preview_url:
          "/api/v2/admin/resources/project/3/media/image/8/thumbnail",
      },
      has_changes: false,
    };
    const withEvent = (previewUrl: string | null) => ({
      ...event,
      resource: { ...event.resource, preview_url: previewUrl },
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          data: [event],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [withEvent("/media/thumbnails/projects/3/cover.jpg")],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [
            withEvent(
              "/api/v2/admin/resources/project/4/media/image/8/thumbnail",
            ),
          ],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [
            withEvent(
              "/api/v2/admin/resources/project/3/media/attachment/8/thumbnail",
            ),
          ],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      listAuditEvents({ page: 1, entity: "all", action: "" }),
    ).resolves.toMatchObject({
      data: [
        {
          resource: {
            preview_url:
              "/api/v2/admin/resources/project/3/media/image/8/thumbnail",
          },
        },
      ],
    });
    for (let index = 0; index < 3; index += 1) {
      await expect(
        listAuditEvents({ page: 1, entity: "all", action: "" }),
      ).rejects.toMatchObject({ code: "invalid_response" });
    }
  });

  it("sends same-origin CSRF mutations and never stores password data", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ data: user }, { status: 201 }))
      .mockResolvedValueOnce(
        Response.json({ data: { ...user, active: false } }),
      )
      .mockResolvedValueOnce(
        Response.json({ data: { id: user.id, session_revoked: true } }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await createAdminUser(
      "member@example.invalid",
      "temporary password",
      false,
      session,
    );
    await setUserActive(user.id, false, session);
    await resetUserPassword(user.id, "replacement password", session);

    expect(fetchMock).toHaveBeenCalledTimes(3);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init).toMatchObject({ credentials: "same-origin" });
      expect(new Headers(init?.headers).get("x-csrf-token")).toBe("csrf-test");
    }
    expect(fetchMock.mock.calls[0]?.[1]?.body).toBe(
      JSON.stringify({
        email: "member@example.invalid",
        password: "temporary password",
        is_admin: false,
      }),
    );
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/v2/admin/users/7/active");
    expect(fetchMock.mock.calls[2]?.[0]).toBe(
      "/api/v2/admin/users/7/reset-password",
    );
  });

  it("starts and stops impersonation with a CSRF-protected POST", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          data: {
            user: { id: 7, email: "member@example.invalid" },
            admin: { id: 4, email: "admin@example.invalid" },
            expires_at: 1,
          },
        }),
      )
      .mockResolvedValueOnce(Response.json({ data: { stopped: true } }));
    vi.stubGlobal("fetch", fetchMock);

    await impersonateUser(7, session);
    await stopImpersonation(session);

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v2/admin/users/7/impersonate",
    );
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      "/api/v2/admin/impersonation/stop",
    );
    for (const [, init] of fetchMock.mock.calls) {
      expect(init).toMatchObject({ method: "POST" });
      expect(new Headers(init?.headers).get("x-csrf-token")).toBe("csrf-test");
    }
  });

  it("reads the optional impersonator of an event and ignores junk", () => {
    expect(parseImpersonator(undefined)).toBeNull();
    expect(parseImpersonator("admin")).toBeNull();
    expect(
      parseImpersonator({
        id: 4,
        name: "Admin",
        email: "admin@example.invalid",
      }),
    ).toEqual({ id: 4, name: "Admin", email: "admin@example.invalid" });
    expect(parseImpersonator({ id: 4, name: "Admin" })).toEqual({
      id: 4,
      name: "Admin",
      email: null,
    });
  });

  it("serializes audit filters and parses entity/page URL state", async () => {
    expect(
      auditSearchFromFilters({
        page: 3,
        entity: "yarn",
        action: " photo_added ",
      }),
    ).toBe("page=3&entity=yarn&action=photo_added");
    expect(
      auditFiltersFromSearch("?page=10001&entity=unknown&action=old_event"),
    ).toEqual({
      page: 1,
      entity: "all",
      action: "old_event",
    });

    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: [
          {
            id: 2,
            actor_email: null,
            actor_avatar_url: null,
            legacy_actor_user_id: 71,
            entity_type: "project",
            entity_id: 6,
            action: "updated",
            created_at: "2026-11-01T12:00:00Z",
            resource: projectResource(6),
            has_changes: true,
          },
        ],
        meta: { page: 1, per_page: 50, has_more: false },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const result = await listAuditEvents({
      page: 1,
      entity: "project",
      action: "updated",
    });
    expect(result.data[0]?.actor_email).toBeNull();
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v2/admin/audit?entity=project&action=updated",
    );
  });

  it("accepts historic nonpositive audit references but keeps event IDs positive", async () => {
    const historicEvent = {
      id: 2,
      actor_email: null,
      actor_avatar_url: null,
      legacy_actor_user_id: 0,
      entity_type: "project",
      entity_id: 0,
      action: "updated",
      created_at: "2026-11-01T12:00:00Z",
      resource: {
        kind: "project",
        id: 0,
        name: null,
        name_source: "unavailable",
        href: "/app/admin/audit/2",
        preview_url: null,
        exists: false,
      },
      has_changes: false,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          data: [historicEvent],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [{ ...historicEvent, id: 0 }],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      listAuditEvents({ page: 1, entity: "all", action: "" }),
    ).resolves.toMatchObject({ data: [historicEvent] });
    await expect(
      listAuditEvents({ page: 1, entity: "all", action: "" }),
    ).rejects.toMatchObject({ code: "invalid_response" });
  });

  it("loads a typed, link-safe audit diff lazily and rejects unknown fields", async () => {
    const event = {
      id: 31,
      actor_email: "owner@example.invalid",
      actor_avatar_url: null,
      legacy_actor_user_id: null,
      entity_type: "project",
      entity_id: 12,
      action: "updated",
      created_at: "2026-11-01T12:00:00Z",
      resource: projectResource(12),
      has_changes: true,
    };
    const payload = {
      data: {
        event,
        changes: {
          version: 1,
          items: [
            {
              field: "project.name",
              before: { type: "text", value: "old name" },
              after: { type: "text", value: "<img src=x onerror=alert(1)>" },
            },
            {
              field: "project.link",
              before: { type: "link_changed", value: true },
              after: { type: "link_changed", value: false },
            },
          ],
        },
        unavailable_reason: null,
      },
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json(payload))
      .mockResolvedValueOnce(
        Response.json({
          data: {
            ...payload.data,
            changes: {
              version: 1,
              items: [
                {
                  field: "project.secret",
                  before: { type: "text", value: "x" },
                  after: { type: "text", value: "y" },
                },
              ],
            },
          },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAuditEventDetail(31)).resolves.toMatchObject({
      event,
      changes: {
        version: 1,
        items: [
          {
            field: "project.name",
            after: { type: "text", value: "<img src=x onerror=alert(1)>" },
          },
          {
            field: "project.link",
            after: { type: "link_changed", value: false },
          },
        ],
      },
      unavailable_reason: null,
    });
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v2/admin/audit/31");
    await expect(getAuditEventDetail(31)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("keeps administrator thumbnails of picture changes and drops foreign links", async () => {
    const event = {
      id: 32,
      actor_email: "owner@example.invalid",
      actor_avatar_url: null,
      legacy_actor_user_id: null,
      entity_type: "project",
      entity_id: 12,
      action: "updated",
      created_at: "2026-11-01T12:00:00Z",
      resource: projectResource(12),
      has_changes: true,
      media_thumbnails: {
        "a.jpg": {
          url: "/api/v2/admin/resources/project/12/media/image/3",
          thumbnail_url:
            "/api/v2/admin/resources/project/12/media/image/3/thumbnail",
        },
        "b.jpg": {
          url: "https://evil.invalid/b.jpg",
          thumbnail_url: "/media/x",
        },
      },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: [event],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      ),
    );
    const page = await listAuditEvents({ page: 1, entity: "all", action: "" });
    expect(page.data[0]?.media_thumbnails).toEqual({
      "a.jpg": {
        url: "/api/v2/admin/resources/project/12/media/image/3",
        thumbnail_url:
          "/api/v2/admin/resources/project/12/media/image/3/thumbnail",
      },
    });
  });

  it("preserves stable errors and rejects unsafe or malformed DTOs", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json(
          {
            error: {
              code: "email_in_use",
              message: "Email already exists",
              fields: { email: "Already used" },
            },
          },
          { status: 409 },
        ),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: [
            {
              id: 1,
              actor_email: "admin@example.invalid",
              actor_avatar_url: null,
              legacy_actor_user_id: null,
              entity_type: "project",
              entity_id: 3,
              action: "created",
              created_at: "2026-11-01T12:00:00Z",
              resource: projectResource(3),
              has_changes: false,
              details: { password: "must not be accepted" },
            },
          ],
          meta: { page: 1, per_page: 50, has_more: false },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      createAdminUser(user.email, "some password", false, session),
    ).rejects.toMatchObject({
      status: 409,
      code: "email_in_use",
      fields: { email: "Already used" },
    } satisfies Partial<ApiError>);
    await expect(
      listAuditEvents({ page: 1, entity: "all", action: "" }),
    ).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  describe("trash lifecycle fields", () => {
    const base = {
      id: 21,
      actor_email: "admin@example.invalid",
      actor_avatar_url: null,
      legacy_actor_user_id: null,
      entity_type: "project",
      entity_id: 3,
      action: "trashed",
      created_at: "2026-11-01T12:00:00Z",
      has_changes: false,
    };
    const trash = {
      deleted_at: "2026-11-01T12:00:00Z",
      purge_at: "2027-01-30T12:00:00Z",
      days_remaining: 80,
      can_restore: true,
    };
    const page = (event: unknown) =>
      Response.json({
        data: [event],
        meta: { page: 1, per_page: 50, has_more: false },
      });
    const list = () => listAuditEvents({ page: 1, entity: "all", action: "" });

    it("keeps old payloads without the new fields valid", async () => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValue(
            page({ ...base, action: "deleted", resource: projectResource(3) }),
          ),
      );
      const [event] = (await list()).data;
      expect(event).toMatchObject({
        system: false,
        subject: null,
        resource: { state: null, trash: null },
      });
    });

    it("reads a trashed entry that is still in the trash without a link", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          page({
            ...base,
            subject: { id: 2, name: "Anna", role: null },
            resource: {
              ...projectResource(3),
              href: "/app/projects/3",
              preview_url:
                "/api/v2/admin/resources/project/3/media/image/8/thumbnail",
              exists: false,
              state: "in_trash",
              trash,
            },
          }),
        ),
      );
      const [event] = (await list()).data;
      expect(event.resource).toMatchObject({
        exists: false,
        state: "in_trash",
        href: null,
        preview_url: null,
        trash,
      });
      expect(event.subject).toEqual({ id: 2, name: "Anna" });
    });

    it("reads the system purge without an actor", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          page({
            ...base,
            actor_email: null,
            action: "purged",
            system: true,
            resource: {
              ...projectResource(3),
              href: null,
              exists: false,
              state: "gone",
            },
          }),
        ),
      );
      const [event] = (await list()).data;
      expect(event).toMatchObject({
        system: true,
        actor_email: null,
        resource: { state: "gone" },
      });
    });

    it("rejects an unknown state or a malformed trash block", async () => {
      for (const resource of [
        { ...projectResource(3), state: "archived" },
        { ...projectResource(3), state: "in_trash", trash: { can_restore: 1 } },
      ]) {
        vi.stubGlobal(
          "fetch",
          vi.fn().mockResolvedValue(page({ ...base, resource })),
        );
        await expect(list()).rejects.toMatchObject({
          code: "invalid_response",
        });
      }
    });

    it("still rejects a live-resource link for a resource that is gone", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          page({
            ...base,
            resource: {
              ...projectResource(3),
              href: "/app/projects/3",
              exists: false,
              state: "gone",
            },
          }),
        ),
      );
      await expect(list()).rejects.toMatchObject({ code: "invalid_response" });
    });
  });
});
