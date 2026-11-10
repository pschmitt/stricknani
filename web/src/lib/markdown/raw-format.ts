// Toolbar actions on Markdown source (the Raw mode), the same rules as Android's MarkdownEdit.kt.
export type RawFormat =
  "bold" | "italic" | "strike" | "heading" | "bullet" | "ordered" | "link";

export interface RawEdit {
  text: string;
  start: number;
  end: number;
}

const HEADING_PREFIX = "### ";
const LINK_PLACEHOLDER = "https://";
const HEADING_RE = /^#{1,6}\s+/;
const BULLET_RE = /^[-*+]\s+/;
const NUMBERED_RE = /^\d+\.\s+/;

export function applyRawFormat(edit: RawEdit, format: RawFormat): RawEdit {
  const start = Math.min(edit.start, edit.end);
  const end = Math.max(edit.start, edit.end);
  const value = { text: edit.text, start, end };
  switch (format) {
    case "bold":
      return toggleWrap(value, "**");
    case "italic":
      return toggleWrap(value, "*");
    case "strike":
      return toggleWrap(value, "~~");
    case "heading":
      return toggleLinePrefix(value, "heading");
    case "bullet":
      return toggleLinePrefix(value, "bullet");
    case "ordered":
      return toggleLinePrefix(value, "ordered");
    case "link":
      return insertLink(value);
  }
}

function isWrapped(
  text: string,
  start: number,
  end: number,
  marker: string,
): boolean {
  const n = marker.length;
  if (start < n || end + n > text.length) return false;
  if (text.slice(start - n, start) !== marker) return false;
  if (text.slice(end, end + n) !== marker) return false;
  // A single * next to another * is the edge of a bold run, not italic.
  if (marker === "*") {
    if (start >= 2 && text[start - 2] === "*") return false;
    if (end + 1 < text.length && text[end + 1] === "*") return false;
  }
  return true;
}

function toggleWrap({ text, start, end }: RawEdit, marker: string): RawEdit {
  const n = marker.length;
  if (isWrapped(text, start, end, marker)) {
    return {
      text:
        text.slice(0, start - n) + text.slice(start, end) + text.slice(end + n),
      start: start - n,
      end: end - n,
    };
  }
  const selected = text.slice(start, end);
  if (
    selected.length >= 2 * n &&
    selected.startsWith(marker) &&
    selected.endsWith(marker) &&
    !(marker === "*" && selected.startsWith("**"))
  ) {
    const inner = selected.slice(n, selected.length - n);
    return {
      text: text.slice(0, start) + inner + text.slice(end),
      start,
      end: start + inner.length,
    };
  }
  return {
    text: text.slice(0, start) + marker + selected + marker + text.slice(end),
    start: start + n,
    end: end + n,
  };
}

type LineKind = "heading" | "bullet" | "ordered";
const KIND_RE: Record<LineKind, RegExp> = {
  heading: /^### /,
  bullet: BULLET_RE,
  ordered: NUMBERED_RE,
};

function stripBlockPrefix(line: string): string {
  return line
    .replace(HEADING_RE, "")
    .replace(BULLET_RE, "")
    .replace(NUMBERED_RE, "");
}

function toggleLinePrefix(
  { text, start, end: selectionEnd }: RawEdit,
  kind: LineKind,
): RawEdit {
  let end = selectionEnd;
  if (end > start && text[end - 1] === "\n") end -= 1;
  const lineStart = text.lastIndexOf("\n", start - 1) + 1;
  const found = text.indexOf("\n", end);
  const lineEnd = found === -1 ? text.length : found;
  const lines = text.slice(lineStart, lineEnd).split("\n");
  const filled = lines.filter((line) => line.trim() !== "");
  const re = KIND_RE[kind];
  const remove = filled.length > 0 && filled.every((line) => re.test(line));
  let number = 0;
  const rewritten = lines.map((line) => {
    if (line.trim() === "" && lines.length > 1) return line;
    if (remove) return line.replace(re, "");
    const bare = stripBlockPrefix(line);
    if (kind === "heading") return HEADING_PREFIX + bare;
    if (kind === "bullet") return `- ${bare}`;
    number += 1;
    return `${number}. ${bare}`;
  });
  const joined = rewritten.join("\n");
  const firstDelta = rewritten[0].length - lines[0].length;
  const totalDelta = joined.length - (lineEnd - lineStart);
  const newStart = Math.max(lineStart, start + firstDelta);
  const newEnd = Math.max(newStart, end + totalDelta);
  return {
    text: text.slice(0, lineStart) + joined + text.slice(lineEnd),
    start: newStart,
    end: newEnd,
  };
}

function insertLink({ text, start, end }: RawEdit): RawEdit {
  const selected = text.slice(start, end);
  const before = text.slice(0, start);
  const after = text.slice(end);
  if (selected === "") {
    return {
      text: `${before}[](${LINK_PLACEHOLDER})${after}`,
      start: start + 1,
      end: start + 1,
    };
  }
  if (selected.startsWith("http://") || selected.startsWith("https://")) {
    return {
      text: `${before}[](${selected})${after}`,
      start: start + 1,
      end: start + 1,
    };
  }
  const urlStart = start + selected.length + 3;
  return {
    text: `${before}[${selected}](${LINK_PLACEHOLDER})${after}`,
    start: urlStart,
    end: urlStart + LINK_PLACEHOLDER.length,
  };
}
