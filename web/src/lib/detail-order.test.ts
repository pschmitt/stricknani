import { describe, expect, it } from "vitest";
import {
  defaultDetailOrder,
  isDefaultDetailOrder,
  moveDetailCard,
  parseDetailOrders,
  sanitizeDetailOrder,
  visibleDetailOrder,
} from "./detail-order.ts";

describe("detail card order", () => {
  it("uses the same defaults as Android", () => {
    expect(defaultDetailOrder("project")).toEqual([
      "details",
      "materials",
      "description",
      "steps",
    ]);
    expect(defaultDetailOrder("yarn")).toEqual([
      "details",
      "description",
      "notes",
      "used_in",
    ]);
  });

  it("drops unknown and duplicate ids and appends missing cards", () => {
    expect(
      sanitizeDetailOrder("yarn", ["notes", "bogus", "notes", "details"]),
    ).toEqual(["notes", "details", "description", "used_in"]);
    expect(sanitizeDetailOrder("project", null)).toEqual(
      defaultDetailOrder("project"),
    );
    expect(sanitizeDetailOrder("project", ["notes"])).toEqual(
      defaultDetailOrder("project"),
    );
  });

  it("keeps the saved order for the cards that are present", () => {
    expect(
      visibleDetailOrder(
        "project",
        ["steps", "details", "materials", "description"],
        ["details", "steps"],
      ),
    ).toEqual(["steps", "details"]);
    expect(
      visibleDetailOrder("yarn", ["notes"], ["details", "notes", "extra"]),
    ).toEqual(["notes", "details", "extra"]);
  });

  it("moves a card and ignores out-of-range moves", () => {
    expect(moveDetailCard(["a", "b", "c"], 1, 0)).toEqual(["b", "a", "c"]);
    expect(moveDetailCard(["a", "b", "c"], 0, 2)).toEqual(["b", "c", "a"]);
    expect(moveDetailCard(["a", "b", "c"], -1, 0)).toEqual(["a", "b", "c"]);
    expect(moveDetailCard(["a", "b", "c"], 1, 5)).toEqual(["a", "b", "c"]);
    expect(moveDetailCard(["a", "b", "c"], 1, 1)).toEqual(["a", "b", "c"]);
  });

  it("recognises the default order", () => {
    expect(isDefaultDetailOrder("yarn", defaultDetailOrder("yarn"))).toBe(true);
    expect(isDefaultDetailOrder("yarn", ["notes"])).toBe(false);
  });

  it("parses the server payload defensively", () => {
    expect(parseDetailOrders(undefined)).toEqual({
      project: defaultDetailOrder("project"),
      yarn: defaultDetailOrder("yarn"),
    });
    expect(
      parseDetailOrders({ project: ["steps", 3, "x"], yarn: "nope" }),
    ).toEqual({
      project: ["steps", "details", "materials", "description"],
      yarn: defaultDetailOrder("yarn"),
    });
  });
});
