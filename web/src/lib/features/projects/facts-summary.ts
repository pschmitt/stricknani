/**
 * Plain-text summaries for the compact facts at the top of the project reader.
 *
 * Fields such as the stitch sample hold Markdown source (images, links,
 * `&nbsp;`). The compact view shows a readable one-line-ish digest of it; the
 * stored value is never modified.
 */

const NAMED_ENTITIES: Record<string, string> = {
  nbsp: " ",
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
};

function decodeEntities(value: string): string {
  return value.replace(
    /&(#x[0-9a-f]+|#\d+|[a-z]+);/gi,
    (match, body: string) => {
      if (body[0] === "#") {
        const code =
          body[1] === "x" || body[1] === "X"
            ? Number.parseInt(body.slice(2), 16)
            : Number.parseInt(body.slice(1), 10);
        if (!Number.isFinite(code) || code <= 0 || code > 0x10ffff)
          return match;
        return code === 0xa0 ? " " : String.fromCodePoint(code);
      }
      return NAMED_ENTITIES[body.toLowerCase()] ?? match;
    },
  );
}

/** Reduce Markdown-ish text to collapsed plain text. Empty input gives "". */
export function plainTextSummary(value: string | null | undefined): string {
  if (!value) return "";
  return decodeEntities(
    value
      // Images carry no readable text.
      .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
      // Links keep their text.
      .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1"),
  )
    .replace(/[ \s]+/g, " ")
    .trim();
}

/** Number of additional linked yarns beyond the first shown by name. */
export function extraCount(total: number): number {
  return total > 1 ? total - 1 : 0;
}
