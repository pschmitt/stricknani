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
