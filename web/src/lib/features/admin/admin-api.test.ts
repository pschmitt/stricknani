import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import {
  auditFiltersFromSearch,
  auditSearchFromFilters,
  createAdminUser,
  listAdminUsers,
  listAuditEvents,
  resetUserPassword,
  setUserActive,
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
});
