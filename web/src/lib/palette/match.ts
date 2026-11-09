/**
 * Matching and ranking behind the command palette. The Android palette
 * (ui/palette/PaletteMatcher.kt) implements the same rules: keep both in step.
 *
 * Folding keeps the text length: every character maps to exactly one character,
 * lower case and without accents (a, o, u for the umlauts; s for the sharp s),
 * so match positions in the folded text are positions in the original.
 */

export type MatchKind = "exact" | "prefix" | "word" | "substring" | "fuzzy";

export type Range = [number, number];

export interface PaletteField {
  text: string;
  /** How much a hit in this field counts (title 1, details less). */
  weight: number;
}

export function foldChar(char: string): string {
  if (char === "ß") return "s";
  const first = char.normalize("NFD")[0] ?? char;
  return first.toLowerCase()[0] ?? first;
}

export function fold(text: string): string {
  let out = "";
  for (const char of text) {
    // Astral characters (two UTF-16 units) stay as they are, length-preserving.
    out += char.length === 1 ? foldChar(char) : char;
  }
  return out;
}

/** The words of a query, folded; empty for a blank query. */
export function queryWords(query: string): string[] {
  return fold(query).split(/\s+/).filter(Boolean);
}

const BOUNDARY = /[\s\-_/.:@,·]/;

const SCORE = { exact: 1000, prefix: 800, word: 600, substring: 400 } as const;

/** Where one folded word matches in one folded text, or null. */
export function matchWord(
  word: string,
  text: string,
): { score: number; kind: MatchKind; ranges: Range[] } | null {
  if (!word || !text) return null;
  if (text === word)
    return { score: SCORE.exact, kind: "exact", ranges: [[0, text.length]] };
  if (text.startsWith(word))
    return { score: SCORE.prefix, kind: "prefix", ranges: [[0, word.length]] };
  let at = text.indexOf(word);
  let substringAt = -1;
  while (at >= 0) {
    if (BOUNDARY.test(text[at - 1] ?? "")) {
      return {
        score: SCORE.word - Math.min(at, 40) / 4,
        kind: "word",
        ranges: [[at, at + word.length]],
      };
    }
    if (substringAt < 0) substringAt = at;
    at = text.indexOf(word, at + 1);
  }
  if (substringAt >= 0) {
    return {
      score: SCORE.substring - Math.min(substringAt, 40) / 4,
      kind: "substring",
      ranges: [[substringAt, substringAt + word.length]],
    };
  }
  if (word.length < 3) return null;
  // Fuzzy: the letters in order, with gaps; tighter is better.
  const ranges: Range[] = [];
  let position = 0;
  let gaps = 0;
  let previous = -1;
  for (const char of word) {
    const found = text.indexOf(char, position);
    if (found < 0) return null;
    if (previous >= 0) gaps += found - previous - 1;
    ranges.push([found, found + 1]);
    previous = found;
    position = found + 1;
  }
  if (gaps > word.length * 3) return null;
  return { score: Math.max(50, 250 - gaps * 8), kind: "fuzzy", ranges };
}

/** Every word must be found in some field; the best field counts, weighted. */
export function scoreEntry(
  words: string[],
  fields: PaletteField[],
): { score: number; ranges: Map<number, Range[]> } | null {
  if (!words.length) return { score: 0, ranges: new Map() };
  const folded = fields.map((field) => fold(field.text));
  let total = 0;
  const ranges = new Map<number, Range[]>();
  for (const word of words) {
    let best = -1;
    let bestField = -1;
    let bestRanges: Range[] = [];
    folded.forEach((text, index) => {
      const hit = matchWord(word, text);
      if (!hit) return;
      const score = hit.score * fields[index].weight;
      if (score > best) {
        best = score;
        bestField = index;
        bestRanges = hit.ranges;
      }
    });
    if (best < 0) return null;
    total += best;
    ranges.set(bestField, [...(ranges.get(bestField) ?? []), ...bestRanges]);
  }
  return { score: total, ranges };
}

/** Sorted, merged ranges. */
export function mergeRanges(ranges: Range[]): Range[] {
  const sorted = ranges
    .map((range): Range => [range[0], range[1]])
    .sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const out: Range[] = [];
  for (const [start, end] of sorted) {
    const last = out[out.length - 1];
    if (last && start <= last[1]) last[1] = Math.max(last[1], end);
    else out.push([start, end]);
  }
  return out;
}

export interface HighlightPart {
  text: string;
  match: boolean;
}

/** The text cut at the ranges, for rendering marks without innerHTML. */
export function highlightParts(
  text: string,
  ranges: Range[] | undefined,
): HighlightPart[] {
  if (!ranges?.length) return [{ text, match: false }];
  const parts: HighlightPart[] = [];
  let position = 0;
  for (const [start, end] of mergeRanges(ranges)) {
    if (start > position)
      parts.push({ text: text.slice(position, start), match: false });
    parts.push({ text: text.slice(start, end), match: true });
    position = end;
  }
  if (position < text.length)
    parts.push({ text: text.slice(position), match: false });
  return parts;
}

/** The http(s) link a query is, or null (typed or pasted, nothing else in it). */
export function detectUrl(query: string): string | null {
  const text = query.trim();
  if (!text || /\s/.test(text)) return null;
  if (!/^https?:\/\//i.test(text)) return null;
  try {
    const url = new URL(text);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    return url.hostname ? text : null;
  } catch {
    return null;
  }
}

export type PaletteGroupId =
  "actions" | "projects" | "yarns" | "categories" | "pages" | "settings";

/** The order of the groups, the same on Android. */
export const GROUP_ORDER: readonly PaletteGroupId[] = [
  "actions",
  "projects",
  "yarns",
  "categories",
  "pages",
  "settings",
];

/** Rows shown per group before "Show more". */
export const GROUP_LIMIT = 8;

export interface Ranked<T> {
  item: T;
  score: number;
  order: number;
}

/** Best first; equal scores keep their original order. */
export function rank<T>(entries: Ranked<T>[]): Ranked<T>[] {
  return [...entries].sort((a, b) => b.score - a.score || a.order - b.order);
}
