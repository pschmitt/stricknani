import { describe, expect, it } from "vitest";
import { wordDiff } from "./word-diff";

const changed = (segments: { text: string; changed: boolean }[]) =>
  segments.filter((s) => s.changed).map((s) => s.text);

describe("wordDiff", () => {
  it("marks only the words that differ", () => {
    const diff = wordDiff("ui-audit ok 4281", "ui-audit slow 6844");
    expect(changed(diff.before)).toEqual(["ok 4281"]);
    expect(changed(diff.after)).toEqual(["slow 6844"]);
    expect(diff.before.map((s) => s.text).join("")).toBe("ui-audit ok 4281");
    expect(diff.after.map((s) => s.text).join("")).toBe("ui-audit slow 6844");
  });

  it("handles pure additions and removals", () => {
    expect(changed(wordDiff("", "Fresh text").after)).toEqual(["Fresh text"]);
    expect(wordDiff("", "Fresh text").before).toEqual([]);
    expect(changed(wordDiff("a b c", "a c").before)).toEqual(["b"]);
  });

  it("keeps identical text unmarked", () => {
    expect(changed(wordDiff("same words", "same words").after)).toEqual([]);
  });

  it("falls back to whole values for very long texts", () => {
    const long = Array.from({ length: 600 }, (_, i) => `w${i}`).join(" ");
    const diff = wordDiff(long, `${long} x`);
    expect(diff.before).toEqual([{ text: long, changed: true }]);
  });
});
