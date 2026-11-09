import { describe, expect, it } from "vitest";
import { categoryOptions, selectedCategory } from "./category-options";

describe("categoryOptions", () => {
  it("sorts categories by name and drops duplicates and blanks", () => {
    expect(
      categoryOptions(
        [
          { name: "Socks" },
          { name: "blankets" },
          { name: " " },
          { name: "SOCKS" },
        ],
        "",
      ),
    ).toEqual(["blankets", "Socks"]);
  });

  it("keeps a current category that is not in the list", () => {
    expect(categoryOptions([{ name: "Socks" }], "Hats")).toEqual([
      "Hats",
      "Socks",
    ]);
  });

  it("does not repeat a current category that differs only in case", () => {
    expect(categoryOptions([{ name: "Socks" }], "socks")).toEqual(["Socks"]);
  });
});

describe("selectedCategory", () => {
  it("maps the current value to the matching option", () => {
    expect(selectedCategory(["Socks"], "socks")).toBe("Socks");
    expect(selectedCategory(["Socks"], "")).toBe("");
    expect(selectedCategory(["Socks"], " Hats ")).toBe("Hats");
  });
});
