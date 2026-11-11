import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { accountSections, buildStaticEntries } from "./entries";
import { GROUP_LIMIT, type PaletteGroupId } from "./match";
import { buildGroups, categoryEntry, projectEntry, yarnEntry } from "./results";

const resolvePath = (path: string) => `/app${path === "/" ? "" : path}`;

function statics(url: string | null = null, isAdmin = false) {
  return buildStaticEntries({ locale: "en", isAdmin, resolvePath, url });
}

const none = new Set<PaletteGroupId>();

describe("static entries", () => {
  it("lists the pages of the navigation and Admin only for administrators", () => {
    const titles = (admin: boolean) =>
      statics(null, admin)
        .filter((entry) => entry.group === "pages")
        .map((entry) => entry.title);
    expect(titles(false)).toEqual([
      "Home",
      "Projects",
      "Yarns",
      "Create",
      "Categories",
      "Gauge calculator",
      "Trash",
      "Account",
      "About",
    ]);
    expect(titles(true)).toContain("Admin");
  });

  it("points settings at anchors that exist on the Account page", () => {
    const source = ["AccountSettings.svelte", "BackupsSection.svelte"]
      .map((file) =>
        readFileSync(
          new URL(`../features/account/${file}`, import.meta.url),
          "utf8",
        ),
      )
      .join("\n");
    for (const section of accountSections)
      expect(source, section.anchor).toContain(`id="${section.anchor}"`);
  });

  it("offers both imports first for a link, linked to the editors", () => {
    const entries = statics("https://example.com/a?x=1&y=b");
    const imports = entries.filter((entry) =>
      entry.id.startsWith("action:import"),
    );
    expect(imports.map((entry) => entry.href)).toEqual([
      "/app/projects/new?url=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3Db&start=1",
      "/app/yarns/new?url=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3Db&start=1",
    ]);
  });
});

describe("buildGroups", () => {
  const project = projectEntry(
    {
      id: 7,
      name: "Müller Pullover",
      category: "Sweaters",
      tags: ["wool"],
      is_favorite: false,
      updated_at: "",
      preview_url: null,
    },
    "/app/projects/7",
  );
  const yarn = yarnEntry(
    {
      id: 3,
      name: "Merino",
      brand: "Drops",
      colorway: "Grün",
      weight_category: null,
      is_favorite: false,
      updated_at: "",
      preview_url: null,
    },
    "/app/yarns/3",
  );
  const category = categoryEntry(
    { id: 1, name: "Sweaters" },
    "/app/categories",
  );
  const entries = [...statics(), project, yarn, category];

  function ids(query: string, url: string | null = null) {
    return buildGroups({
      query,
      entries: url ? [...statics(url), project, yarn] : entries,
      serverIds: new Set<string>(),
      expanded: none,
      url,
    }).map((group) => [group.id, group.rows.map((row) => row.entry.id)]);
  }

  it("shows recent items, main pages and actions for an empty query", () => {
    const groups = ids("");
    expect(groups.map(([id]) => id)).toEqual([
      "actions",
      "projects",
      "yarns",
      "pages",
    ]);
  });

  it("finds a project through umlaut-free typing and groups in a fixed order", () => {
    const groups = ids("sweaters");
    expect(groups).toEqual([
      ["projects", ["project:7"]],
      ["categories", ["category:1"]],
    ]);
    expect(ids("gruen").length).toBe(0);
    expect(ids("grun")).toEqual([["yarns", ["yarn:3"]]]);
  });

  it("puts exact and prefix matches before fuzzy ones", () => {
    const rows = buildGroups({
      query: "yarn",
      entries: statics(),
      serverIds: new Set(),
      expanded: none,
      url: null,
    });
    const pages = rows.find((group) => group.id === "pages")!.rows;
    expect(pages[0].entry.title).toBe("Yarns");
  });

  it("limits a group and lists all once expanded", () => {
    const many = Array.from({ length: 12 }, (_, index) =>
      projectEntry(
        {
          id: index + 1,
          name: `Sock ${index}`,
          category: null,
          tags: [],
          is_favorite: false,
          updated_at: "",
          preview_url: null,
        },
        `/app/projects/${index + 1}`,
      ),
    );
    const build = (expanded: Set<PaletteGroupId>) =>
      buildGroups({
        query: "sock",
        entries: many,
        serverIds: new Set(),
        expanded,
        url: null,
      })[0];
    expect(build(none).rows).toHaveLength(GROUP_LIMIT);
    expect(build(none).total).toBe(12);
    expect(build(new Set<PaletteGroupId>(["projects"])).rows).toHaveLength(12);
  });

  it("leads with the two imports for a pasted link", () => {
    const groups = ids("https://example.com/p", "https://example.com/p");
    expect(groups).toEqual([
      ["actions", ["action:import-project", "action:import-yarn"]],
    ]);
  });
});
