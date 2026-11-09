/** Characters that end a tag while typing: a comma or any whitespace. */
const SEPARATORS = /[\s,]+/;

export interface TagSplit {
  /** Finished tags, in typing order, without empty pieces. */
  tags: string[];
  /** Text still being typed: the last piece when no separator follows it. */
  rest: string;
}

/**
 * Splits the tag input at spaces and commas. With `commitAll` (Enter, the add
 * button, a paste, saving) every piece becomes a tag; while typing, the last
 * piece stays in the field until a separator follows it.
 */
export function splitTagInput(text: string, commitAll = false): TagSplit {
  const pieces = text.split(SEPARATORS);
  const rest = commitAll ? "" : (pieces.pop() ?? "");
  return { tags: pieces.filter((piece) => piece.length > 0), rest };
}

/** Appends tags the list does not hold yet (case-insensitive), keeping order. */
export function mergeTags(existing: string[], added: string[]): string[] {
  const merged = [...existing];
  const seen = new Set(merged.map((tag) => tag.toLowerCase()));
  for (const tag of added) {
    const key = tag.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(tag);
  }
  return merged.length === existing.length ? existing : merged;
}

/** A tag the user already uses, with the number of projects that carry it. */
export interface TagCount {
  tag: string;
  count: number;
}

/** How many suggestions the popup shows at most. */
export const MAX_TAG_SUGGESTIONS = 8;

const byUsage = (a: TagCount, b: TagCount): number =>
  b.count - a.count ||
  a.tag.localeCompare(b.tag, undefined, { sensitivity: "base" }) ||
  (a.tag < b.tag ? -1 : a.tag > b.tag ? 1 : 0);

/**
 * The tags to suggest for what is typed. Tags already chosen are left out
 * (case-insensitive). An empty query gives the most used tags; otherwise tags
 * that start with the query come first, then tags that merely contain it, each
 * group most used first, then by name.
 */
export function suggestTags(
  known: readonly TagCount[],
  query: string,
  chosen: readonly string[],
  limit = MAX_TAG_SUGGESTIONS,
): TagCount[] {
  const needle = query.trim().toLowerCase();
  const taken = new Set(chosen.map((tag) => tag.toLowerCase()));
  const open = known.filter((entry) => !taken.has(entry.tag.toLowerCase()));
  if (!needle) return [...open].sort(byUsage).slice(0, limit);
  const prefix: TagCount[] = [];
  const inside: TagCount[] = [];
  for (const entry of open) {
    const index = entry.tag.toLowerCase().indexOf(needle);
    if (index === 0) prefix.push(entry);
    else if (index > 0) inside.push(entry);
  }
  return [...prefix.sort(byUsage), ...inside.sort(byUsage)].slice(0, limit);
}
