import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createProject,
  deleteProjectAttachment,
  deleteProjectImage,
  filtersFromSearch,
  getProject,
  getYarn,
  listProjects,
  listProjectTags,
  parseProjectDetail,
  searchFromFilters,
  setProjectFavorite,
  updateProject,
  uploadProjectAttachment,
  uploadProjectImage,
} from "./project-api";

const summary = {
  id: 42,
  name: "River path socks",
  category: "Socks",
  tags: ["cables", "weekend"],
  is_favorite: false,
  updated_at: "2026-10-31T12:00:00.000000Z",
  preview_url: "/media/thumbnails/projects/42/thumb_cover.jpg",
};

const detail = {
  ...summary,
  yarn: "Soft wool",
  needles: "3.5 mm",
  stitch_sample: "24 stitches over 10 cm",
  description: "**Knit in the round.**",
  description_html: "<p><strong>Knit in the round.</strong></p>\n",
  notes: null,
  notes_html: null,
  other_materials: null,
  other_materials_html: null,
  link: null,
  link_archive: null,
  is_ai_enhanced: false,
  created_at: "2026-10-30T12:00:00.000000Z",
  yarn_ids: [7],
  steps: [
    {
      id: 80,
      title: "Heel flap",
      description: "Knit 24 rows.",
      description_html: "<p>Knit 24 rows.</p>\n",
      step_number: 2,
    },
  ],
  images: [
    {
      id: 15,
      url: "/media/projects/42/cover.jpg",
      thumbnail_url: "/media/thumbnails/projects/42/thumb_cover.jpg",
      alt_text: "Blue wool socks",
      image_type: "photo",
      is_title_image: true,
      is_stitch_sample: false,
      step_id: null,
    },
  ],
  attachments: [],
};

const session = {
  user: {
    id: 9,
    email: "synthetic@example.test",
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

const write = {
  name: "River path socks",
  category: "Socks",
  needles: "3.5 mm",
  stitch_sample: "24 stitches over 10 cm",
  description: "**Knit in the round.**",
  notes: null,
  other_materials: null,
  tags: ["cables", "weekend"],
  link: "https://patterns.example.test/socks",
  is_ai_enhanced: false,
  yarn_ids: [7],
  steps: [
    {
      id: 80,
      title: "Heel flap",
      description: "Knit 24 rows.",
      step_number: 1,
    },
  ],
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("project URL state and API DTOs", () => {
  it("reads and serializes search, category, tag, favorite, and page filters", () => {
    const filters = filtersFromSearch(
      "?q=heel%20turn&category=Socks&tag=weekend&favorite=true&page=3",
    );

    expect(filters).toEqual({
      query: "heel turn",
      category: "Socks",
      tag: "weekend",
      favorite: true,
      page: 3,
    });
    expect(searchFromFilters(filters)).toBe(
      "q=heel+turn&category=Socks&tag=weekend&favorite=true&page=3",
    );
  });

  it("resets invalid pagination to the first page", () => {
    expect(filtersFromSearch("?page=-4&favorite=false")).toEqual({
      query: "",
      category: "",
      tag: "",
      favorite: false,
      page: 1,
    });
  });

  it("loads the paginated numeric-ID summary through the actual client contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: [summary],
        meta: { page: 2, per_page: 50, has_more: false },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const page = await listProjects(
      "q=heel+turn&page=2",
      new AbortController().signal,
    );

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v2/projects?q=heel+turn&page=2",
    );
    expect(page.meta).toEqual({ page: 2, per_page: 50, has_more: false });
    expect(page.data[0]).toMatchObject({ id: 42, name: "River path socks" });
  });

  it("requests tag suggestions in the selected library scope", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(async () =>
        Response.json({ data: [{ tag: "wool", count: 1 }] }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await listProjectTags(undefined, "all");
    await listProjectTags(undefined, "mine");
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/api/v2/projects/tags?scope=all",
      "/api/v2/projects/tags",
    ]);
  });

  it("derives a linked yarn preview from its owner-scoped photo DTO", async () => {
    const yarn = {
      id: 7,
      name: "Soft wool",
      brand: null,
      colorway: null,
      fiber_content: "wool",
      weight_category: "DK",
      recommended_needles: null,
      notes_html: null,
      link: null,
      weight_grams: null,
      length_meters: null,
      photos: [
        {
          id: 1,
          thumbnail_url: "/media/thumbnails/yarns/7/first.jpg",
          is_primary: false,
        },
        {
          id: 2,
          thumbnail_url: "/media/thumbnails/yarns/7/primary.jpg",
          is_primary: true,
        },
      ],
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ data: yarn }))
      .mockResolvedValueOnce(
        Response.json({
          data: {
            ...yarn,
            photos: [
              {
                ...yarn.photos[0],
                thumbnail_url: "/media/thumbnails/yarns/8/other.jpg",
              },
            ],
          },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      getYarn(7, new AbortController().signal),
    ).resolves.toMatchObject({
      preview_url: "/media/thumbnails/yarns/7/primary.jpg",
    });
    await expect(
      getYarn(7, new AbortController().signal),
    ).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("rejects malformed list and detail resources", async () => {
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
          Response.json({ data: { ...detail, images: [null] } }),
        ),
    );

    await expect(
      listProjects("", new AbortController().signal),
    ).rejects.toMatchObject({ code: "invalid_response" });
    await expect(
      getProject(42, new AbortController().signal),
    ).rejects.toMatchObject({ code: "invalid_response" });
  });

  it("keeps sanitized HTML companion fields separate from raw Markdown", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(Response.json({ data: detail })),
    );

    const project = await getProject(42, new AbortController().signal);

    expect(project.description).toBe("**Knit in the round.**");
    expect(project.description_html).toBe(
      "<p><strong>Knit in the round.</strong></p>\n",
    );
    expect(project.steps[0]?.step_number).toBe(2);
    expect(project.images[0]?.thumbnail_url).toContain("/media/thumbnails/");
  });

  it("reads stitch_sample_html and the canonical cover preview_url", async () => {
    const payload = (extra: Record<string, unknown>) =>
      Response.json({ data: { ...detail, ...extra } });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          payload({
            stitch_sample_html: "<p>24 <em>stitches</em></p>",
            preview_url: "/media/thumbnails/projects/42/canonical.jpg",
          }),
        )
        .mockResolvedValueOnce(payload({ stitch_sample_html: null }))
        .mockResolvedValueOnce(payload({ stitch_sample_html: 7 })),
    );

    const signal = new AbortController().signal;
    const html = await getProject(42, signal);
    expect(html.stitch_sample_html).toBe("<p>24 <em>stitches</em></p>");
    expect(html.preview_url).toBe(
      "/media/thumbnails/projects/42/canonical.jpg",
    );
    const nothing = await getProject(42, signal);
    expect(nothing.stitch_sample_html).toBeNull();
    await expect(getProject(42, signal)).rejects.toThrow();
  });

  it("takes the cover from the canonical is_primary over a stale title flag", async () => {
    const image = detail.images[0];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce(
        Response.json({
          data: {
            ...detail,
            images: [
              { ...image, id: 13, is_title_image: true, is_primary: false },
              { ...image, id: 24, is_title_image: false, is_primary: true },
            ],
          },
        }),
      ),
    );

    const project = await getProject(42, new AbortController().signal);
    expect(
      project.images.map((each) => [each.id, each.is_title_image]),
    ).toEqual([
      [13, false],
      [24, true],
    ]);
  });

  it("sends CSRF on the real favorite route without a body", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(Response.json({ data: detail }));
    vi.stubGlobal("fetch", fetchMock);
    await setProjectFavorite(42, true, {
      user: {
        id: 9,
        email: "synthetic@example.test",
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
      locale: "en",
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];

    expect(url).toBe("/api/v2/projects/42/favorite");
    expect(init.method).toBe("PUT");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("csrf-fixture");
    expect(init.body).toBeUndefined();
  });

  it("creates and updates project fields through the real v2 mutations", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ data: detail }, { status: 201 }))
      .mockResolvedValueOnce(Response.json({ data: detail }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createProject(write, session)).resolves.toMatchObject({
      id: 42,
    });
    await expect(
      updateProject(42, write, detail.updated_at, session),
    ).resolves.toMatchObject({ id: 42 });

    const [createUrl, createInit] = fetchMock.mock.calls[0] as [
      string,
      RequestInit,
    ];
    expect(createUrl).toBe("/api/v2/projects");
    expect(createInit.method).toBe("POST");
    expect(new Headers(createInit.headers).get("x-csrf-token")).toBe(
      "csrf-fixture",
    );
    expect(JSON.parse(String(createInit.body))).toEqual(write);

    const [updateUrl, updateInit] = fetchMock.mock.calls[1] as [
      string,
      RequestInit,
    ];
    expect(updateUrl).toBe("/api/v2/projects/42");
    expect(updateInit.method).toBe("PUT");
    expect(JSON.parse(String(updateInit.body))).toEqual({
      ...write,
      expected_updated_at: detail.updated_at,
    });
  });

  it("forwards optional project and step image URLs unchanged on create", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(Response.json({ data: detail }, { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);
    const value = {
      ...write,
      image_urls: ["https://images.example.test/project.jpg"],
      steps: [
        {
          ...write.steps[0]!,
          image_urls: ["https://images.example.test/step.jpg"],
        },
      ],
    };

    await createProject(value, session);

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual(value);
  });

  it("keeps the latest conflict DTO available for an explicit draft choice", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            error: { code: "conflict", message: "changed", current: detail },
          },
          { status: 409 },
        ),
      ),
    );

    await expect(
      updateProject(42, write, detail.updated_at, session),
    ).rejects.toMatchObject({
      status: 409,
      code: "conflict",
      current: { id: 42, updated_at: detail.updated_at },
    });
    expect(parseProjectDetail(detail).id).toBe(42);
  });

  it("uploads image and attachment multipart bodies with CSRF and deletes media", async () => {
    const image = detail.images[0];
    if (!image) throw new Error("fixture image missing");
    const attachment = {
      id: 18,
      filename: "pattern.pdf",
      original_filename: "pattern.pdf",
      content_type: "application/pdf",
      size_bytes: 1024,
      url: "/media/projects/42/pattern.pdf",
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ data: image }, { status: 201 }))
      .mockResolvedValueOnce(
        Response.json({ data: attachment }, { status: 201 }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const imageFile = new File(["image"], "cover.png", { type: "image/png" });
    const attachmentFile = new File(["pattern"], "pattern.pdf", {
      type: "application/pdf",
    });

    await expect(
      uploadProjectImage(42, imageFile, "Cover image", session),
    ).resolves.toMatchObject({ id: 15, is_title_image: true });
    await expect(
      uploadProjectAttachment(42, attachmentFile, session),
    ).resolves.toMatchObject({ id: 18, original_filename: "pattern.pdf" });
    await deleteProjectImage(42, 15, session);
    await deleteProjectAttachment(42, 18, session);

    const [, imageInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v2/projects/42/images/title",
    );
    expect(new Headers(imageInit.headers).get("x-csrf-token")).toBe(
      "csrf-fixture",
    );
    expect(imageInit.body).toBeInstanceOf(FormData);
    expect(new Headers(imageInit.headers).get("content-type")).toBeNull();
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      "/api/v2/projects/42/attachments",
    );
    expect(fetchMock.mock.calls[2]?.[0]).toBe("/api/v2/projects/42/images/15");
    expect(fetchMock.mock.calls[3]?.[0]).toBe(
      "/api/v2/projects/42/attachments/18",
    );
  });
});
