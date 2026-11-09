import type { Category } from "../features/categories/category-api.ts";
import type { ProjectSummary } from "../features/projects/project-api.ts";
import type { YarnSummary } from "../features/yarns/yarn-api.ts";
import type { PaletteEntry } from "./entries.ts";
import {
  GROUP_LIMIT,
  GROUP_ORDER,
  queryWords,
  rank,
  scoreEntry,
  type PaletteGroupId,
  type Range,
} from "./match.ts";

export interface PaletteRow {
  entry: PaletteEntry;
  score: number;
  /** Highlight ranges for the title (0), the line below (1) and the keywords (2). */
  ranges: Map<number, Range[]>;
}

export interface PaletteGroup {
  id: PaletteGroupId;
  /** More rows matched than are listed. */
  total: number;
  rows: PaletteRow[];
}

/** Rows per group when nothing is typed (recent items and the main pages). */
const EMPTY_LIMITS: Partial<Record<PaletteGroupId, number>> = {
  actions: 3,
  projects: 5,
  yarns: 5,
  pages: 8,
};

export function projectEntry(
  project: ProjectSummary,
  href: string,
): PaletteEntry {
  return {
    id: `project:${project.id}`,
    group: "projects",
    title: project.name,
    sub: project.category ?? undefined,
    keywords: project.tags,
    icon: "projects",
    href,
    imageUrl: project.preview_url,
  };
}

export function yarnEntry(yarn: YarnSummary, href: string): PaletteEntry {
  const sub = [yarn.brand, yarn.colorway].filter(Boolean).join(" · ");
  return {
    id: `yarn:${yarn.id}`,
    group: "yarns",
    title: yarn.name,
    sub: sub || undefined,
    keywords: [yarn.weight_category ?? ""].filter(Boolean),
    icon: "yarns",
    href,
    imageUrl: yarn.preview_url,
  };
}

export function categoryEntry(category: Category, href: string): PaletteEntry {
  return {
    id: `category:${category.id}`,
    group: "categories",
    title: category.name,
    keywords: [],
    icon: "categories",
    href,
  };
}

/** Score one entry: title first, then the line below, then keywords. */
export function scoreRow(
  words: string[],
  entry: PaletteEntry,
  fromServer: boolean,
): PaletteRow | null {
  const hit = scoreEntry(words, [
    { text: entry.title, weight: 1 },
    { text: entry.sub ?? "", weight: 0.6 },
    { text: entry.keywords.join(" "), weight: 0.5 },
  ]);
  if (hit) return { entry, score: hit.score, ranges: hit.ranges };
  // The server may match more than the palette can see (notes, source); keep its hits last.
  if (fromServer) return { entry, score: 1, ranges: new Map() };
  return null;
}

/**
 * The groups to show: matching rows ranked in each group, the groups in the
 * fixed order, at most GROUP_LIMIT rows each unless expanded.
 */
export function buildGroups(options: {
  query: string;
  entries: PaletteEntry[];
  /** Ids of entries that came from the server search. */
  serverIds: Set<string>;
  expanded: ReadonlySet<PaletteGroupId>;
  /** A link was typed: its import actions come first. */
  url: string | null;
}): PaletteGroup[] {
  const words = queryWords(options.query);
  const empty = words.length === 0;
  const byGroup = new Map<PaletteGroupId, PaletteRow[]>();
  options.entries.forEach((entry, order) => {
    if (empty && !(entry.group in EMPTY_LIMITS)) return;
    const row = options.url
      ? entry.id.startsWith("action:import")
        ? { entry, score: 100000 - order, ranges: new Map<number, Range[]>() }
        : null
      : scoreRow(words, entry, options.serverIds.has(entry.id));
    if (!row) return;
    byGroup.set(entry.group, [...(byGroup.get(entry.group) ?? []), row]);
  });
  const groups: PaletteGroup[] = [];
  for (const id of GROUP_ORDER) {
    const rows = byGroup.get(id);
    if (!rows?.length) continue;
    const ranked = rank(
      rows.map((row, order) => ({ item: row, score: row.score, order })),
    ).map((entry) => entry.item);
    const limit = empty
      ? (EMPTY_LIMITS[id] ?? GROUP_LIMIT)
      : options.expanded.has(id)
        ? Infinity
        : GROUP_LIMIT;
    groups.push({ id, total: ranked.length, rows: ranked.slice(0, limit) });
  }
  return groups;
}
