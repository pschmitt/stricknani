import { afterEach, describe, expect, it, vi } from "vitest";
import {
  applyProjectSuggestion,
  applyYarnSuggestion,
  buildSuggestions,
  fetchRemoteSuggestions,
  loadFilterSuggestions,
  matchRank,
  parseRemoteSuggestions,
  foldedQuery,
  type RemoteSuggestions,
} from "./search-suggestions";
import { filtersFromSearch } from "./features/projects/project-api";
import { yarnFiltersFromSearch } from "./features/yarns/yarn-api";

afterEach(() => {
  vi.unstubAllGlobals();
});

const remote: RemoteSuggestions = {
  tags: ["Mütze", "Kinder-Mütze", "socks"],
  categories: ["Mützen", "Schals"],
  owners: [
    { id: 7, name: "Ben", avatarUrl: "/avatars/7" },
    { id: 9, name: "Mia", avatarUrl: null },
  ],
};

function labels(query: string, kind: "projects" | "yarns" = "projects") {
  return buildSuggestions(query, kind, "en", remote).map(
    (suggestion) => `${suggestion.group}:${suggestion.label}`,
  );
}

describe("matching", () => {
  it("ignores case and diacritics", () => {
    for (const typed of ["mü", "mu", "MÜ", "Mu", " mü "]) {
      expect(matchRank(foldedQuery(typed), "Mütze")).toBe(0);
    }
    expect(matchRank(foldedQuery("mütze"), "mutze")).toBe(0);
  });

  it("accepts a prefix of the value or of one of its words, not the middle", () => {
    expect(matchRank("mu", "Kinder-Mütze")).toBe(1);
    expect(matchRank("bl", "Baby blanket")).toBe(1);
    expect(matchRank("tze", "Mütze")).toBeNull();
    expect(matchRank("mu", "Wintermütze")).toBeNull();
    expect(matchRank("", "Mütze")).toBeNull();
  });
});

describe("grouping", () => {
  it("lists tags, categories, status, owners and the rest in that order", () => {
    expect(labels("mü")).toEqual([
      "tags:Mütze",
      "tags:Kinder-Mütze",
      "categories:Mützen",
    ]);
    expect(labels("s")).toEqual([
      "tags:socks",
      "categories:Schals",
      "owners:Shared with me",
    ]);
    expect(labels("m")).toEqual([
      "tags:Mütze",
      "tags:Kinder-Mütze",
      "categories:Mützen",
      "owners:Mine",
      "owners:Mia",
      "owners:Shared with me",
    ]);
  });

  it("offers the fixed filters by their labels", () => {
    expect(labels("do")).toEqual(["status:Done"]);
    expect(labels("in")).toEqual(["status:Project in progress"]);
    expect(labels("tra")).toEqual(["other:Trash"]);
    expect(labels("fav")).toEqual(["other:Favorites only"]);
  });

  it("has no status for yarns", () => {
    expect(labels("do", "yarns")).toEqual([]);
    expect(labels("tra", "yarns")).toEqual(["other:Trash"]);
  });

  it("offers nothing for a blank query and caps each group", () => {
    expect(labels("  ")).toEqual([]);
    const many: RemoteSuggestions = {
      tags: Array.from({ length: 9 }, (_, index) => `tag ${index}`),
      categories: [],
      owners: [],
    };
    expect(buildSuggestions("tag", "projects", "en", many)).toHaveLength(5);
  });

  it("carries no owners the server did not send", () => {
    const members: RemoteSuggestions = { ...remote, owners: [] };
    const owners = buildSuggestions("b", "projects", "en", members).filter(
      (suggestion) => suggestion.id.startsWith("owner:"),
    );
    expect(owners).toEqual([]);
    expect(
      buildSuggestions("m", "projects", "en", members).map((s) => s.label),
    ).toContain("Mine");
  });

  it("offers owners of the server's list as owner ids", () => {
    const ben = buildSuggestions("be", "projects", "en", remote);
    expect(ben).toHaveLength(1);
    expect(ben[0]?.action).toEqual({ type: "owner", value: "7" });
    expect(ben[0]?.avatarUrl).toBe("/avatars/7");
  });
});

describe("applying", () => {
  const filters = filtersFromSearch("q=hat&favorite=true&page=3");

  it("sets one filter, drops the typed text and goes back to page one", () => {
    expect(
      applyProjectSuggestion(filters, { type: "tag", value: "Mütze" }),
    ).toMatchObject({ tag: "Mütze", query: "", page: 1, favorite: true });
    expect(
      applyProjectSuggestion(filters, { type: "category", value: "Mützen" }),
    ).toMatchObject({ category: "Mützen", query: "" });
    expect(
      applyProjectSuggestion(filters, { type: "status", value: "done" }),
    ).toMatchObject({ status: "done" });
    expect(
      applyProjectSuggestion(filters, { type: "owner", value: "me" }),
    ).toMatchObject({ owner: "me" });
    expect(applyProjectSuggestion(filters, { type: "trash" })).toMatchObject({
      trashed: "only",
    });
  });

  it("applies yarn filters and ignores kinds yarns do not have", () => {
    const yarn = yarnFiltersFromSearch("q=wool");
    expect(applyYarnSuggestion(yarn, { type: "favorite" })).toMatchObject({
      favorite: true,
      query: "",
    });
    expect(
      applyYarnSuggestion(yarn, { type: "owner", value: "4" }),
    ).toMatchObject({ owner: "4" });
    expect(applyYarnSuggestion(yarn, { type: "tag", value: "x" })).toBe(yarn);
  });
});

describe("server payload", () => {
  it("keeps only well-formed entries", () => {
    expect(
      parseRemoteSuggestions({
        data: {
          tags: [{ tag: "a", count: 2 }, { tag: 3 }, null],
          categories: [{ name: "Hats" }, { name: " " }],
          owners: [
            { id: 4, name: " Ben ", avatar_url: "/a" },
            { id: -1, name: "x" },
            { id: 5, name: "Mia", avatar_url: "https://evil.example/a" },
          ],
        },
      }),
    ).toEqual({
      tags: ["a"],
      categories: ["Hats"],
      owners: [
        { id: 4, name: "Ben", avatarUrl: "/a" },
        { id: 5, name: "Mia", avatarUrl: null },
      ],
    });
    expect(parseRemoteSuggestions("nope")).toEqual({
      tags: [],
      categories: [],
      owners: [],
    });
  });

  it("asks the server with the kind and the typed text", async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            data: {
              tags: [{ tag: "Mütze", count: 1 }],
              categories: [],
              owners: [],
            },
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
    );
    vi.stubGlobal("fetch", fetchMock);
    const result = await fetchRemoteSuggestions(
      "yarns",
      " mü ",
      new AbortController().signal,
    );
    expect(result.tags).toEqual(["Mütze"]);
    const url = String((fetchMock.mock.calls[0] as unknown[])[0]);
    expect(url).toContain("/api/v2/search/suggestions?");
    expect(url).toContain("kind=yarns");
    expect(url).toContain("q=m%C3%BC");
  });

  it("follows the Trash pill and the owner filter", async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({ data: { tags: [], categories: [], owners: [] } }),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
    );
    vi.stubGlobal("fetch", fetchMock);
    await fetchRemoteSuggestions(
      "projects",
      "win",
      new AbortController().signal,
      { trashed: true, owner: "7" },
    );
    const url = String((fetchMock.mock.calls[0] as unknown[])[0]);
    expect(url).toContain("trashed=only");
    expect(url).toContain("owner=7");
    await fetchRemoteSuggestions(
      "projects",
      "win",
      new AbortController().signal,
    );
    const plain = String((fetchMock.mock.calls[1] as unknown[])[0]);
    expect(plain).not.toContain("trashed");
    expect(plain).not.toContain("owner");
  });

  it("still offers the fixed filters when the server fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("offline");
      }),
    );
    const result = await loadFilterSuggestions(
      "projects",
      "tra",
      "en",
      new AbortController().signal,
    );
    expect(result.map((s) => s.label)).toEqual(["Trash"]);
  });

  it("does not ask for a blank query", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    expect(
      await loadFilterSuggestions(
        "projects",
        " ",
        "en",
        new AbortController().signal,
      ),
    ).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
