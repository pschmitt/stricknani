import { describe, expect, it } from "vitest";
import { mergeTags, splitTagInput, suggestTags } from "./tag-input";

describe("splitTagInput", () => {
  it("keeps the piece being typed in the field", () => {
    expect(splitTagInput("test")).toEqual({ tags: [], rest: "test" });
    expect(splitTagInput("test, tes")).toEqual({ tags: ["test"], rest: "tes" });
  });

  it("chips a piece as soon as a comma or space follows it", () => {
    expect(splitTagInput("test,")).toEqual({ tags: ["test"], rest: "" });
    expect(splitTagInput("test ")).toEqual({ tags: ["test"], rest: "" });
    expect(splitTagInput("test, test2 ")).toEqual({
      tags: ["test", "test2"],
      rest: "",
    });
  });

  it("commits every piece, also a pasted one", () => {
    expect(splitTagInput("a, b c", true)).toEqual({
      tags: ["a", "b", "c"],
      rest: "",
    });
  });

  it("ignores empty pieces", () => {
    expect(splitTagInput(" , ,, a ,  ,b", true).tags).toEqual(["a", "b"]);
    expect(splitTagInput("  ,", true)).toEqual({ tags: [], rest: "" });
    expect(splitTagInput("")).toEqual({ tags: [], rest: "" });
  });
});

describe("mergeTags", () => {
  it("adds new tags in order", () => {
    expect(mergeTags(["a"], ["b", "c"])).toEqual(["a", "b", "c"]);
  });

  it("skips duplicates, also within the added tags and by case", () => {
    expect(mergeTags(["a"], ["A", "b", "b"])).toEqual(["a", "b"]);
  });

  it("returns the same list when nothing is new", () => {
    const existing = ["a"];
    expect(mergeTags(existing, ["a"])).toBe(existing);
  });
});

describe("suggestTags", () => {
  const known = [
    { tag: "winter", count: 5 },
    { tag: "wool", count: 2 },
    { tag: "Gift", count: 4 },
    { tag: "socks", count: 4 },
    { tag: "newborn", count: 1 },
    { tag: "snow", count: 3 },
  ];
  const names = (found: { tag: string }[]) => found.map((entry) => entry.tag);

  it("lists the most used tags for an empty query, then by name", () => {
    expect(names(suggestTags(known, "", []))).toEqual([
      "winter",
      "Gift",
      "socks",
      "snow",
      "wool",
      "newborn",
    ]);
    expect(suggestTags(known, "  ", [], 2)).toHaveLength(2);
  });

  it("puts prefix matches before substring matches, ignoring case", () => {
    expect(names(suggestTags(known, "W", []))).toEqual([
      "winter",
      "wool",
      "snow",
      "newborn",
    ]);
    expect(names(suggestTags(known, "no", []))).toEqual(["snow"]);
    expect(names(suggestTags(known, "GIF", []))).toEqual(["Gift"]);
  });

  it("leaves out chosen tags and unrelated ones", () => {
    expect(names(suggestTags(known, "w", ["WINTER"]))).toEqual([
      "wool",
      "snow",
      "newborn",
    ]);
    expect(suggestTags(known, "zzz", [])).toEqual([]);
  });

  it("caps the list", () => {
    const many = Array.from({ length: 20 }, (_, index) => ({
      tag: `tag${index}`,
      count: index,
    }));
    const found = suggestTags(many, "tag", []);
    expect(found).toHaveLength(8);
    expect(found[0]?.tag).toBe("tag19");
  });
});
