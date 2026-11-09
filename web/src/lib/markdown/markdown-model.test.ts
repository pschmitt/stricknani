import { describe, expect, it } from "vitest";
import {
  canEditVisually,
  docToMarkdown,
  markdownToDoc,
} from "./markdown-model.ts";

const roundTrip = (source: string, lines = false) =>
  docToMarkdown(markdownToDoc(source, lines), lines);

describe("markdown model round trip", () => {
  const samples: Record<string, string> = {
    heading: "### Gauge\n\nWork even.",
    emphasis: "Use **bold**, *italic* and ~~struck~~ words.",
    list: "- one\n- two\n- three",
    ordered: "1. cast on\n2. knit\n3. bind off",
    nested: "- a\n  - b\n    - c\n- d",
    link: "See [the pattern](https://example.com/a?b=1) now.",
    image: "![Swatch](/media/projects/12/step-3.jpg)",
    umlauts: "Möhre, Größe 38, Füße über Äste",
    paragraphs: "First paragraph.\n\nSecond paragraph.",
    quote: "> Remember the yarn over.",
    code: "Use `k2tog` here.",
  };

  for (const [name, source] of Object.entries(samples)) {
    it(`keeps ${name} unchanged`, () => {
      expect(roundTrip(source)).toBe(source);
      expect(canEditVisually(source)).toBe(true);
    });
  }

  it("keeps a hard break as a line break", () => {
    expect(roundTrip("line one  \nline two")).toMatch(
      /line one( {2}|\\)\nline two/,
    );
  });

  it("serializes an empty document as the empty string", () => {
    expect(roundTrip("")).toBe("");
  });

  it("treats soft-wrapped prose as one paragraph without data loss", () => {
    expect(canEditVisually("a soft\nwrapped line")).toBe(true);
  });

  it("keeps every line of a line field", () => {
    const source = "2 buttons\nstitch markers\n\ncable needle";
    expect(roundTrip(source, true)).toBe(source);
    expect(canEditVisually(source, true)).toBe(true);
  });
});

describe("canEditVisually", () => {
  it("accepts empty text", () => {
    expect(canEditVisually("")).toBe(true);
  });

  it("sends tables to the raw editor", () => {
    expect(canEditVisually("| a | b |\n| - | - |\n| 1 | 2 |")).toBe(false);
  });
});
