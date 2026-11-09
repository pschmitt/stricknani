import { describe, expect, it } from "vitest";
import {
  detectUrl,
  fold,
  highlightParts,
  matchWord,
  mergeRanges,
  queryWords,
  rank,
  scoreEntry,
} from "./match";

describe("fold", () => {
  it("lowers case and drops accents without changing the length", () => {
    expect(fold("Käse Öl Über")).toBe("kase ol uber");
    expect(fold("Straße")).toBe("strase");
    expect(fold("Crème brûlée")).toHaveLength("Crème brûlée".length);
  });

  it("splits a query into folded words", () => {
    expect(queryWords("  Wolle  Grün ")).toEqual(["wolle", "grun"]);
    expect(queryWords("   ")).toEqual([]);
  });
});

describe("matchWord", () => {
  it("ranks exact above prefix above word start above substring above fuzzy", () => {
    const exact = matchWord("socks", "socks")!;
    const prefix = matchWord("sock", "socks")!;
    const word = matchWord("sock", "warm socks")!;
    const sub = matchWord("ock", "socks")!;
    const fuzzy = matchWord("scs", "socks")!;
    const scores = [exact, prefix, word, sub, fuzzy].map((m) => m.score);
    expect(scores).toEqual([...scores].sort((a, b) => b - a));
    expect(new Set(scores).size).toBe(5);
    expect(fuzzy.kind).toBe("fuzzy");
  });

  it("does not fuzzy match one or two letters, or letters out of order", () => {
    expect(matchWord("sk", "socks")).toBeNull();
    expect(matchWord("kcs", "socks")).toBeNull();
  });

  it("finds the highlight ranges", () => {
    expect(matchWord("sock", "warm socks")!.ranges).toEqual([[5, 9]]);
    expect(matchWord("sks", "socks")!.ranges).toEqual([
      [0, 1],
      [3, 4],
      [4, 5],
    ]);
  });
});

describe("scoreEntry", () => {
  const fields = [
    { text: "Müller Pullover", weight: 1 },
    { text: "Jumpers", weight: 0.6 },
  ];

  it("matches umlauts typed without them", () => {
    expect(scoreEntry(["muller"], fields)).not.toBeNull();
  });

  it("needs every word", () => {
    expect(scoreEntry(["pullover", "zzz"], fields)).toBeNull();
    expect(scoreEntry(["pullover", "jump"], fields)).not.toBeNull();
  });

  it("counts the title more than the line below", () => {
    const title = scoreEntry(["pullover"], fields)!;
    const sub = scoreEntry(["jump"], fields)!;
    expect(title.score).toBeGreaterThan(sub.score);
    expect(title.ranges.get(0)).toEqual([[7, 15]]);
    expect(sub.ranges.get(1)).toEqual([[0, 4]]);
  });

  it("scores a blank query as zero", () => {
    expect(scoreEntry([], fields)).toEqual({ score: 0, ranges: new Map() });
  });
});

describe("rank", () => {
  it("sorts by score and keeps the original order of ties", () => {
    const ranked = rank([
      { item: "a", score: 1, order: 0 },
      { item: "b", score: 5, order: 1 },
      { item: "c", score: 1, order: 2 },
    ]);
    expect(ranked.map((entry) => entry.item)).toEqual(["b", "a", "c"]);
  });
});

describe("highlightParts", () => {
  it("cuts the text at the ranges", () => {
    expect(
      highlightParts("warm socks", [
        [5, 7],
        [6, 9],
      ]),
    ).toEqual([
      { text: "warm ", match: false },
      { text: "socks".slice(0, 4), match: true },
      { text: "s", match: false },
    ]);
    expect(highlightParts("abc", undefined)).toEqual([
      { text: "abc", match: false },
    ]);
  });

  it("merges touching ranges", () => {
    expect(
      mergeRanges([
        [3, 4],
        [0, 1],
        [1, 2],
      ]),
    ).toEqual([
      [0, 2],
      [3, 4],
    ]);
  });
});

describe("detectUrl", () => {
  it("accepts an http(s) link, typed or pasted", () => {
    expect(detectUrl("https://example.com/pattern?id=3")).toBe(
      "https://example.com/pattern?id=3",
    );
    expect(detectUrl("  http://example.org/a  ")).toBe("http://example.org/a");
  });

  it("rejects anything else", () => {
    expect(detectUrl("example.com")).toBeNull();
    expect(detectUrl("ftp://example.com")).toBeNull();
    expect(detectUrl("https://example.com is nice")).toBeNull();
    expect(detectUrl("javascript:alert(1)")).toBeNull();
    expect(detectUrl("https://")).toBeNull();
    expect(detectUrl("")).toBeNull();
  });
});
