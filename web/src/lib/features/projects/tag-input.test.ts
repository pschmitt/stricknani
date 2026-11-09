import { describe, expect, it } from "vitest";
import { mergeTags, splitTagInput } from "./tag-input";

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
