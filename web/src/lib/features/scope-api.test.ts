import { afterEach, describe, expect, it, vi } from "vitest";
import { loadFavoriteProjects, loadRecentProjects } from "./home/home-api";
import { getProject, listProjects } from "./projects/project-api";
import { listYarns } from "./yarns/yarn-api";

const owner = {
  id: 7,
  email: "anika@example.test",
  name: "Anika",
  avatar_url: "/api/v2/admin/users/7/avatar/gravatar",
};

const project = {
  id: 5,
  name: "Socks",
  category: null,
  tags: [],
  is_favorite: false,
  updated_at: "2026-11-01T12:00:00Z",
  preview_url: null,
  owner,
};

function stubList(data: unknown[], scope = "all") {
  const fetchMock = vi.fn().mockImplementation(() =>
    Promise.resolve(
      Response.json({
        data,
        meta: { page: 1, per_page: 50, has_more: false, scope },
      }),
    ),
  );
  vi.stubGlobal("fetch", fetchMock);
  return (call = 0) =>
    new URL(String(fetchMock.mock.calls[call]?.[0]), "http://x");
}

afterEach(() => vi.unstubAllGlobals());

describe("scope and owner in list requests", () => {
  it("sends scope and owner_id when asked and keeps other filters", async () => {
    const url = stubList([project]);
    const page = await listProjects("q=sock", new AbortController().signal, {
      scope: "all",
      ownerId: 7,
    });
    expect(url().searchParams.get("q")).toBe("sock");
    expect(url().searchParams.get("scope")).toBe("all");
    expect(url().searchParams.get("owner_id")).toBe("7");
    expect(page.meta.scope).toBe("all");
    expect(page.data[0]?.owner).toEqual(owner);
  });

  it("sends nothing extra without a scope (server default)", async () => {
    const url = stubList([]);
    await listProjects("", new AbortController().signal);
    expect(url().search).toBe("");
  });

  it("scopes yarn lists the same way", async () => {
    const url = stubList([]);
    await listYarns("q=wool", new AbortController().signal, { scope: "mine" });
    expect(url().searchParams.get("scope")).toBe("mine");
    expect(url().searchParams.has("owner_id")).toBe(false);
  });

  it("passes the Home scope to every section", async () => {
    const url = stubList([project]);
    await loadFavoriteProjects(new AbortController().signal, "mine");
    await loadRecentProjects(new AbortController().signal, "all");
    expect(url(0).searchParams.get("scope")).toBe("mine");
    expect(url(0).searchParams.get("favorite")).toBe("true");
    expect(url(1).searchParams.get("scope")).toBe("all");
  });

  it("parses owner on project and yarn details, and tolerates its absence", async () => {
    const detail = {
      ...project,
      yarn: null,
      needles: null,
      stitch_sample: null,
      description: null,
      description_html: null,
      notes: null,
      notes_html: null,
      other_materials: null,
      other_materials_html: null,
      link: null,
      link_archive: null,
      is_ai_enhanced: false,
      created_at: "2026-10-31T12:00:00Z",
      yarn_ids: [],
      steps: [],
      images: [],
      attachments: [],
    };
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ data: detail }))
        .mockResolvedValueOnce(
          Response.json({ data: { ...detail, owner: undefined } }),
        ),
    );
    const signal = new AbortController().signal;
    expect((await getProject(5, signal)).owner).toEqual(owner);
    expect("owner" in (await getProject(5, signal))).toBe(false);
  });
});
