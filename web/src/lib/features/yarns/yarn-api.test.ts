import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getLinkedProject,
  getYarn,
  listYarns,
  parseYarnDetail,
  setYarnFavorite,
  uploadYarnPhoto,
  updateYarn,
  yarnFiltersFromSearch,
  yarnSearchFromFilters,
} from "./yarn-api";

const summary = {
  id: 42,
  name: "Highland wool",
  brand: "North Mill",
  colorway: "Deep lake",
  weight_category: "DK",
  is_favorite: false,
  updated_at: "2026-11-01T12:00:00.000000Z",
  preview_url: "/media/yarns/42/cover.jpg",
};

const detail = {
  ...summary,
  description: "Soft, springy wool.",
  description_html: "<p>Soft, springy wool.</p>\n",
  dye_lot: "D-17",
  fiber_content: "100% wool",
  recommended_needles: "4 mm",
  weight_grams: 100,
  length_meters: 210,
  notes: null,
  notes_html: null,
  link: null,
  link_archive: null,
  is_ai_enhanced: false,
  created_at: "2026-10-31T12:00:00.000000Z",
  project_ids: [8],
  photos: [
    {
      id: 7,
      url: "/media/yarns/42/photo.jpg",
      thumbnail_url: "/media/thumbnails/yarns/42/photo.jpg",
      alt_text: "Blue skein",
      is_primary: true,
    },
  ],
};

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

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("yarn browser API contract", () => {
  it("keeps search, favorite, and pagination in normalized URL state", () => {
    const filters = yarnFiltersFromSearch(
      "?q=blue%20wool&favorite=true&page=4",
    );
    expect(filters).toEqual({
      query: "blue wool",
      favorite: true,
      owner: "",
      trashed: "",
      page: 4,
    });
    expect(yarnSearchFromFilters(filters)).toBe(
      "q=blue+wool&favorite=true&page=4",
    );
    expect(yarnFiltersFromSearch("?page=0").page).toBe(1);
  });

  it("loads a paginated summary through the same-origin v2 client", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: [summary],
        meta: { page: 1, per_page: 50, has_more: true },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const result = await listYarns(
      "q=blue+Wool&favorite=true",
      new AbortController().signal,
    );

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v2/yarns?q=blue+Wool&favorite=true",
    );
    expect(result.meta).toEqual({ page: 1, per_page: 50, has_more: true });
    expect(result.data[0]?.id).toBe(42);
  });

  it("derives detail preview from photo metadata and keeps safe HTML separate", () => {
    const yarn = parseYarnDetail(detail);
    expect(yarn.preview_url).toBe("/media/thumbnails/yarns/42/photo.jpg");
    expect(yarn.description).toBe("Soft, springy wool.");
    expect(yarn.description_html).toBe("<p>Soft, springy wool.</p>\n");
    expect(yarn.project_ids).toEqual([8]);
  });

  it("loads linked project labels through the owner-scoped v2 detail route", async () => {
    const project = {
      id: 8,
      name: "Lake socks",
      category: "Socks",
      preview_url: "/media/thumbnails/projects/8/current-cover.jpg",
      images: [
        {
          id: 2,
          is_title_image: false,
          thumbnail_url: "/media/thumbnails/projects/8/first.jpg",
        },
        {
          id: 3,
          is_title_image: true,
          thumbnail_url: "/media/thumbnails/projects/8/title.jpg",
        },
      ],
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ data: project }))
      .mockResolvedValueOnce(
        Response.json({
          data: {
            ...project,
            preview_url: "/media/thumbnails/projects/9/other.jpg",
          },
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          data: {
            ...project,
            images: [
              {
                ...project.images[0],
                thumbnail_url: "/media/thumbnails/projects/9/other.jpg",
              },
            ],
          },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);
    await expect(getLinkedProject(8)).resolves.toEqual({
      id: 8,
      name: "Lake socks",
      category: "Socks",
      preview_url: "/media/thumbnails/projects/8/current-cover.jpg",
    });
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v2/projects/8");
    await expect(getLinkedProject(8)).rejects.toMatchObject({
      code: "invalid_response",
    });
    await expect(getLinkedProject(8)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("rejects string IDs, invalid measurements, and malformed photo DTOs", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          Response.json({
            data: [{ ...summary, id: "42" }],
            meta: { page: 1, per_page: 50, has_more: false },
          }),
        )
        .mockResolvedValueOnce(
          Response.json({ data: { ...detail, weight_grams: -1 } }),
        )
        .mockResolvedValueOnce(
          Response.json({
            data: { ...detail, photos: [{ ...detail.photos[0], id: "7" }] },
          }),
        ),
    );

    await expect(
      listYarns("", new AbortController().signal),
    ).rejects.toMatchObject({ code: "invalid_response" });
    await expect(getYarn(42)).rejects.toMatchObject({
      code: "invalid_response",
    });
    await expect(getYarn(42)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("sends CSRF and the loaded timestamp for CAS updates", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(Response.json({ data: detail }));
    vi.stubGlobal("fetch", fetchMock);
    await updateYarn(
      42,
      {
        name: "Highland wool",
        description: null,
        brand: null,
        colorway: null,
        dye_lot: null,
        fiber_content: null,
        weight_category: null,
        recommended_needles: null,
        weight_grams: null,
        length_meters: null,
        notes: null,
        link: null,
        is_ai_enhanced: false,
      },
      detail.updated_at,
      session,
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];

    expect(url).toBe("/api/v2/yarns/42");
    expect(init.method).toBe("PUT");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("csrf-fixture");
    expect(JSON.parse(String(init.body))).toMatchObject({
      expected_updated_at: detail.updated_at,
    });
  });

  it("sets and clears a favorite using the v2 methods", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ data: detail }))
      .mockResolvedValueOnce(Response.json({ data: detail }));
    vi.stubGlobal("fetch", fetchMock);
    await setYarnFavorite(42, true, session);
    await setYarnFavorite(42, false, session);

    expect(
      fetchMock.mock.calls.map((call) => (call[1] as RequestInit).method),
    ).toEqual(["PUT", "DELETE"]);
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/yarns/42/favorite",
      "/api/v2/yarns/42/favorite",
    ]);
  });

  it("uploads multipart photos with CSRF and blocks an oversized file locally", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: {
          id: 10,
          url: "/media/yarns/42/new.png",
          thumbnail_url: "/media/thumbnails/yarns/42/new.png",
          alt_text: null,
          is_primary: false,
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["pixels"], "yarn.png", { type: "image/png" });
    await uploadYarnPhoto(42, file, session);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];

    expect(url).toBe("/api/v2/yarns/42/photos");
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("csrf-fixture");
    expect(init.body).toBeInstanceOf(FormData);
    expect(new Headers(init.headers).has("content-type")).toBe(false);

    const largeFile = new File(
      [new Uint8Array(25 * 1024 * 1024 + 1)],
      "large.png",
      {
        type: "image/png",
      },
    );
    await expect(uploadYarnPhoto(42, largeFile, session)).rejects.toMatchObject(
      {
        status: 413,
        code: "payload_too_large",
      },
    );
  });
});
