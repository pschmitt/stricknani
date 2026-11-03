/** A run of text in a word diff; `changed` marks words only on this side. */
export interface DiffSegment {
  text: string;
  changed: boolean;
}

export interface WordDiff {
  before: DiffSegment[];
  after: DiffSegment[];
}

/** Above this many token pairs the diff is skipped and both sides are shown whole. */
const MAX_CELLS = 250_000;

function tokenize(text: string): string[] {
  return text.split(/(\s+)/).filter((token) => token.length > 0);
}

function push(segments: DiffSegment[], text: string, changed: boolean) {
  const last = segments.at(-1);
  if (last && last.changed === changed) last.text += text;
  else segments.push({ text, changed });
}

/**
 * Word-level diff of two texts (longest common subsequence over words and whitespace), so a
 * reader sees exactly which words were removed and added instead of comparing two blocks.
 * Whitespace-only differences are not highlighted. Very long texts fall back to "all changed".
 */
export function wordDiff(beforeText: string, afterText: string): WordDiff {
  const a = tokenize(beforeText);
  const b = tokenize(afterText);
  if (a.length * b.length > MAX_CELLS) {
    return {
      before: beforeText ? [{ text: beforeText, changed: true }] : [],
      after: afterText ? [{ text: afterText, changed: true }] : [],
    };
  }
  // lengths[i][j] = LCS length of a[i..] and b[j..]
  const lengths = Array.from({ length: a.length + 1 }, () =>
    new Array<number>(b.length + 1).fill(0),
  );
  for (let i = a.length - 1; i >= 0; i -= 1) {
    for (let j = b.length - 1; j >= 0; j -= 1) {
      lengths[i][j] =
        a[i] === b[j]
          ? lengths[i + 1][j + 1] + 1
          : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
    }
  }
  const before: DiffSegment[] = [];
  const after: DiffSegment[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      push(before, a[i], false);
      push(after, b[j], false);
      i += 1;
      j += 1;
    } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
      push(before, a[i], a[i].trim().length > 0);
      i += 1;
    } else {
      push(after, b[j], b[j].trim().length > 0);
      j += 1;
    }
  }
  for (; i < a.length; i += 1) push(before, a[i], a[i].trim().length > 0);
  for (; j < b.length; j += 1) push(after, b[j], b[j].trim().length > 0);
  return { before: bridgeSpaces(before), after: bridgeSpaces(after) };
}

/** Joins "changed, space, changed" into one highlight so a replaced phrase reads as one mark. */
function bridgeSpaces(segments: DiffSegment[]): DiffSegment[] {
  const result: DiffSegment[] = [];
  segments.forEach((segment, index) => {
    const bridging =
      !segment.changed &&
      segment.text.trim().length === 0 &&
      segments[index - 1]?.changed === true &&
      segments[index + 1]?.changed === true;
    push(result, segment.text, segment.changed || bridging);
  });
  return result;
}
