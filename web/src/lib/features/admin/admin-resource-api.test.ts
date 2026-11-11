import { afterEach, describe, expect, it, vi } from "vitest";
import { getAdminResource } from "./admin-resource-api";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("admin resource API", () => {
  it("loads a read-only cross-owner project through the admin route", async () => {
    const data = {
      kind: "project",
      id: 12,
      name: "Sample project",
      owner: {
        id: 4,
        email: "owner@example.invalid",
        avatar_url: "/api/v2/admin/users/4/avatar/thumb_avatar.jpg",
      },
      read_only: true,
      resource: {
        id: 12,
        name: "Sample project",
        category: null,
        yarn: null,
        needles: null,
        stitch_sample: null,
        description: "description",
        description_html: "<p>description</p>",
        notes: null,
        notes_html: null,
        other_materials: null,
        other_materials_html: null,
        tags: [],
        link: null,
        link_archive: null,
        is_ai_enhanced: false,
        is_favorite: false,
        status: "in_progress",
        created_at: "2026-11-01T12:00:00Z",
        updated_at: "2026-11-01T12:00:00Z",
        yarn_ids: [],
        steps: [],
        images: [
          {
            id: 8,
            url: "/api/v2/admin/resources/project/12/media/image/8",
            thumbnail_url:
              "/api/v2/admin/resources/project/12/media/image/8/thumbnail",
            alt_text: "",
            image_type: "project",
            is_title_image: true,
            is_stitch_sample: false,
            step_id: null,
          },
        ],
        attachments: [],
      },
    };
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ data }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAdminResource("project", 12)).resolves.toEqual(data);
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v2/admin/resources/project/12",
    );
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      credentials: "same-origin",
    });
  });

  it("rejects malformed or cross-parent media URLs and editable responses", async () => {
    const base = {
      kind: "project",
      id: 12,
      name: "Sample project",
      owner: null,
      read_only: true,
      resource: {
        id: 12,
        name: "Sample project",
        category: null,
        yarn: null,
        needles: null,
        stitch_sample: null,
        description: null,
        description_html: null,
        notes: null,
        notes_html: null,
        other_materials: null,
        other_materials_html: null,
        tags: [],
        link: null,
        link_archive: null,
        is_ai_enhanced: false,
        is_favorite: false,
        created_at: "2026-11-01T12:00:00Z",
        updated_at: "2026-11-01T12:00:00Z",
        yarn_ids: [],
        steps: [],
        images: [],
        attachments: [],
      },
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          data: {
            ...base,
            resource: {
              ...base.resource,
              images: [
                {
                  id: 8,
                  url: "/api/v2/admin/resources/project/99/media/image/8",
                  thumbnail_url:
                    "/api/v2/admin/resources/project/12/media/image/8/thumbnail",
                  alt_text: "",
                  image_type: "project",
                  is_title_image: true,
                  is_stitch_sample: false,
                  step_id: null,
                },
              ],
            },
          },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({ data: { ...base, read_only: false } }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAdminResource("project", 12)).rejects.toMatchObject({
      code: "invalid_response",
    });
    await expect(getAdminResource("project", 12)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });
});
