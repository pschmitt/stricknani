import { describe, expect, it } from "vitest";
import { visiblePeople } from "./people-filter";

const people = [
  { id: 1, name: "Admin-dde39", avatar_url: null },
  { id: 2, name: "Bea", avatar_url: null },
  { id: 3, name: "Carl", avatar_url: null },
  { id: 4, name: "Beatrix", avatar_url: null },
];

describe("people picker list", () => {
  it("shows everyone for an empty query", () => {
    expect(visiblePeople(people, [], "  ")).toHaveLength(4);
  });

  it("shows only matches right after typing, before the server answers", () => {
    expect(visiblePeople(people, [], "bea").map((p) => p.name)).toEqual([
      "Bea",
      "Beatrix",
    ]);
    expect(visiblePeople(people, [], "BEA")).toHaveLength(2);
    expect(visiblePeople(people, [], "zz")).toEqual([]);
  });

  it("drops people who already have access", () => {
    expect(visiblePeople(people, [2], "bea").map((p) => p.id)).toEqual([4]);
  });
});
