import { afterEach, describe, expect, it, vi } from "vitest";
import {
  filtersFromSearch,
  getProject,
  listProjects,
  searchFromFilters,
  setProjectFavorite,
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

  it("sends CSRF on the real favorite route without a body", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(Response.json({ data: detail }));
    vi.stubGlobal("fetch", fetchMock);
    await setProjectFavorite(42, true, {
      user: { id: 9, email: "synthetic@example.test", is_admin: false },
      csrf_token: "csrf-fixture",
      capabilities: {
        signup: false,
        external_imports: false,
        ai: false,
        ocr: false,
        archive: false,
      },
      locale: "en",
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];

    expect(url).toBe("/api/v2/projects/42/favorite");
    expect(init.method).toBe("PUT");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("csrf-fixture");
    expect(init.body).toBeUndefined();
  });
});
