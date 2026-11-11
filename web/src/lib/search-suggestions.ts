/**
 * Typeahead filter suggestions of the project and yarn search bars: typing "mü" offers the
 * "Mütze" tag, the "Mützen" category, the matching status, owner and Trash filters. Selecting one
 * applies that filter. The Android lists implement the same rules and grouping
 * (ui/common/FilterSuggestions.kt): keep both in step.
 *
 * Matching ignores case and diacritics and accepts a prefix of the whole value or of one of its
 * words ("mu" and "mü" find "Mütze", "bl" finds "Baby blanket"). Tags, categories and owners come
 * from `GET /api/v2/search/suggestions`, which sends owners to administrators only; the fixed
 * filters (status, Mine/Shared, Favorites, Trash) are matched here on their translated labels.
 */
import { apiRequest } from "#lib/api.ts";
import { t, type Locale } from "#lib/i18n.svelte.ts";
import type { OwnerChoice } from "#lib/owner-filter.ts";
import { fold } from "#lib/palette/match.ts";
import type {
  ProjectFilters,
  ProjectStatus,
} from "#lib/features/projects/project-api.ts";
import type { YarnFilters } from "#lib/features/yarns/yarn-api.ts";

export type SuggestionKind = "projects" | "yarns";

/** The groups, in the order they are listed (and read by a screen reader). */
export const SUGGESTION_GROUPS = [
  "tags",
  "categories",
  "status",
  "owners",
  "other",
] as const;
export type SuggestionGroup = (typeof SUGGESTION_GROUPS)[number];

/** Suggestions per group. */
export const GROUP_LIMIT = 5;

export type SuggestionAction =
  | { type: "tag"; value: string }
  | { type: "category"; value: string }
  | { type: "status"; value: ProjectStatus }
  | { type: "owner"; value: OwnerChoice }
  | { type: "favorite" }
  | { type: "trash" };

export interface FilterSuggestion {
  /** Unique within one list, stable between keystrokes. */
  id: string;
  group: SuggestionGroup;
  label: string;
  /** An owner's avatar, when the server sent one. */
  avatarUrl?: string | null;
  action: SuggestionAction;
}

/** What the server suggests for the typed text. */
export interface RemoteSuggestions {
  tags: string[];
  categories: string[];
  owners: { id: number; name: string; avatarUrl: string | null }[];
}

/** The folded text of a query, or "" when there is nothing to look for. */
export function foldedQuery(query: string): string {
  return fold(query.trim());
}

/**
 * 0 when the folded query is a prefix of the whole value, 1 when it is a prefix of one of its
 * words, null for no match.
 */
export function matchRank(folded: string, value: string): 0 | 1 | null {
  if (!folded) return null;
  const text = fold(value);
  if (text.startsWith(folded)) return 0;
  return text.split(/[^\p{L}\p{N}]+/u).some((word) => word.startsWith(folded))
    ? 1
    : null;
}

/** The labels of the fixed filters, as the filter pills show them. */
function fixedSuggestions(
  kind: SuggestionKind,
  locale: Locale,
): FilterSuggestion[] {
  const status: FilterSuggestion[] =
    kind === "projects"
      ? [
          {
            id: "status:in_progress",
            group: "status",
            label: t("Project in progress", locale),
            action: { type: "status", value: "in_progress" },
          },
          {
            id: "status:done",
            group: "status",
            label: t("Done", locale),
            action: { type: "status", value: "done" },
          },
        ]
      : [];
  return [
    ...status,
    {
      id: "owner:me",
      group: "owners",
      label: t("Mine", locale),
      action: { type: "owner", value: "me" },
    },
    {
      id: "owner:shared",
      group: "owners",
      label: t("Shared with me", locale),
      action: { type: "owner", value: "shared" },
    },
    {
      id: "other:favorite",
      group: "other",
      label: t("Favorites only", locale),
      action: { type: "favorite" },
    },
    {
      id: "other:trash",
      group: "other",
      label: t("Trash", locale),
      action: { type: "trash" },
    },
  ];
}

/**
 * The suggestions for a typed text, grouped in [SUGGESTION_GROUPS] order: within a group the
 * whole-value prefixes first, then word prefixes, each keeping its source order (fixed filters,
 * then the server's order). Empty for a blank query.
 */
export function buildSuggestions(
  query: string,
  kind: SuggestionKind,
  locale: Locale,
  remote: RemoteSuggestions | null,
): FilterSuggestion[] {
  const folded = foldedQuery(query);
  if (!folded) return [];
  const candidates: FilterSuggestion[] = [
    ...(remote?.tags ?? []).map((tag): FilterSuggestion => ({
      id: `tag:${tag}`,
      group: "tags",
      label: tag,
      action: { type: "tag", value: tag },
    })),
    ...(remote?.categories ?? []).map((name): FilterSuggestion => ({
      id: `category:${name}`,
      group: "categories",
      label: name,
      action: { type: "category", value: name },
    })),
    ...fixedSuggestions(kind, locale),
    ...(remote?.owners ?? []).map((owner): FilterSuggestion => ({
      id: `owner:${owner.id}`,
      group: "owners",
      label: owner.name,
      avatarUrl: owner.avatarUrl,
      action: { type: "owner", value: String(owner.id) as OwnerChoice },
    })),
  ];
  const ranked = candidates.flatMap((candidate, order) => {
    const rank = matchRank(folded, candidate.label);
    return rank === null ? [] : [{ candidate, rank, order }];
  });
  ranked.sort((a, b) => a.rank - b.rank || a.order - b.order);
  return SUGGESTION_GROUPS.flatMap((group) =>
    ranked
      .filter((entry) => entry.candidate.group === group)
      .slice(0, GROUP_LIMIT)
      .map((entry) => entry.candidate),
  );
}

/** The server's payload, defensively: anything malformed is left out. */
export function parseRemoteSuggestions(payload: unknown): RemoteSuggestions {
  const data =
    typeof payload === "object" && payload !== null
      ? (payload as { data?: unknown }).data
      : null;
  const record =
    typeof data === "object" && data !== null
      ? (data as Record<string, unknown>)
      : {};
  const list = (value: unknown): unknown[] =>
    Array.isArray(value) ? value : [];
  const field = (value: unknown, key: string): unknown =>
    typeof value === "object" && value !== null
      ? (value as Record<string, unknown>)[key]
      : undefined;
  const text = (value: unknown): string[] =>
    typeof value === "string" && value.trim() ? [value] : [];
  return {
    tags: list(record.tags).flatMap((item) => text(field(item, "tag"))),
    categories: list(record.categories).flatMap((item) =>
      text(field(item, "name")),
    ),
    owners: list(record.owners).flatMap((item) => {
      const id = field(item, "id");
      const name = field(item, "name");
      const avatar = field(item, "avatar_url");
      if (
        typeof id !== "number" ||
        !Number.isSafeInteger(id) ||
        id <= 0 ||
        typeof name !== "string" ||
        !name.trim()
      )
        return [];
      return [
        {
          id,
          name: name.trim(),
          avatarUrl:
            typeof avatar === "string" && avatar.startsWith("/")
              ? avatar
              : null,
        },
      ];
    }),
  };
}

/** The list filters the suggestions follow: the trash while its pill is on, one owner's items. */
export interface SuggestionScope {
  trashed?: boolean;
  owner?: string;
}

export async function fetchRemoteSuggestions(
  kind: SuggestionKind,
  query: string,
  signal: AbortSignal,
  scope: SuggestionScope = {},
): Promise<RemoteSuggestions> {
  const params = new URLSearchParams({ q: query.trim(), kind });
  if (scope.trashed) params.set("trashed", "only");
  if (scope.owner) params.set("owner", scope.owner);
  return parseRemoteSuggestions(
    await apiRequest(`/api/v2/search/suggestions?${params}`, { signal }),
  );
}

/**
 * The suggestions for the text typed into a list's search bar. When the server cannot be reached
 * the fixed filters are still offered; an aborted request rejects.
 */
export async function loadFilterSuggestions(
  kind: SuggestionKind,
  query: string,
  locale: Locale,
  signal: AbortSignal,
  scope: SuggestionScope = {},
): Promise<FilterSuggestion[]> {
  if (!foldedQuery(query)) return [];
  let remote: RemoteSuggestions | null = null;
  try {
    remote = await fetchRemoteSuggestions(kind, query, signal, scope);
  } catch (error) {
    if (signal.aborted) throw error;
  }
  return buildSuggestions(query, kind, locale, remote);
}

/** The project list filters after picking a suggestion; the typed text is dropped. */
export function applyProjectSuggestion(
  filters: ProjectFilters,
  action: SuggestionAction,
): ProjectFilters {
  const next = { ...filters, query: "", page: 1 };
  switch (action.type) {
    case "tag":
      return { ...next, tag: action.value };
    case "category":
      return { ...next, category: action.value };
    case "status":
      return { ...next, status: action.value };
    case "owner":
      return { ...next, owner: action.value };
    case "favorite":
      return { ...next, favorite: true };
    case "trash":
      return { ...next, trashed: "only" };
  }
}

/** The yarn list filters after picking a suggestion; yarns have no tags, categories or status. */
export function applyYarnSuggestion(
  filters: YarnFilters,
  action: SuggestionAction,
): YarnFilters {
  const next = { ...filters, query: "", page: 1 };
  switch (action.type) {
    case "owner":
      return { ...next, owner: action.value };
    case "favorite":
      return { ...next, favorite: true };
    case "trash":
      return { ...next, trashed: "only" };
    default:
      return filters;
  }
}
