import { afterEach, describe, expect, it, vi } from "vitest";
import {
  HOME_PREVIEW_LIMIT,
  loadFavoriteProjects,
  loadFavoriteYarns,
  loadRecentProjects,
} from "./home-api";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("home dashboard API", () => {
  it("makes three bounded first-page requests and keeps server order", async () => {
    const projects = Array.from({ length: 8 }, (_, index) => ({
      id: index + 1,
      name: `Project ${index + 1}`,
      category: "Socks",
      tags: [],
      is_favorite: true,
      updated_at: `2026-11-0${8 - index}T12:00:00Z`,
      preview_url: null,
    }));
    const yarns = Array.from({ length: 8 }, (_, index) => ({
      id: index + 101,
      name: `Yarn ${index + 1}`,
      brand: null,
      colorway: null,
      weight_category: null,
      is_favorite: true,
      updated_at: `2026-11-0${8 - index}T12:00:00Z`,
      preview_url: null,
    }));
    const controller = new AbortController();
    const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      return Promise.resolve(
        Response.json({
          data: url.includes("/yarns") ? yarns : projects,
          meta: { page: 1, per_page: 50, has_more: true },
        }),
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    const [favoriteProjects, favoriteYarns, recentProjects] = await Promise.all(
      [
        loadFavoriteProjects(controller.signal),
        loadFavoriteYarns(controller.signal),
        loadRecentProjects(controller.signal),
      ],
    );

    expect(favoriteProjects.map((item) => item.id)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(favoriteYarns.map((item) => item.id)).toEqual([
      101, 102, 103, 104, 105, 106,
    ]);
    expect(recentProjects.map((item) => item.id)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(favoriteProjects).toHaveLength(HOME_PREVIEW_LIMIT);
    expect(favoriteYarns).toHaveLength(HOME_PREVIEW_LIMIT);
    expect(recentProjects).toHaveLength(HOME_PREVIEW_LIMIT);
    expect(fetchMock.mock.calls.map(([input]) => String(input)).sort()).toEqual(
      [
        "/api/v2/projects",
        "/api/v2/projects?favorite=true",
        "/api/v2/yarns?favorite=true",
      ],
    );
    for (const [, init] of fetchMock.mock.calls) {
      expect(init?.signal).toBe(controller.signal);
      expect(String(init?.body ?? "")).not.toContain("per_page");
    }
  });
});
