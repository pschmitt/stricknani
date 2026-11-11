import { afterEach, describe, expect, it, vi } from "vitest";
import {
  listOwners,
  ownerChoiceFromParam,
  ownerChoiceIsUser,
} from "./owner-filter";
import {
  filtersFromSearch,
  searchFromFilters,
} from "./features/projects/project-api";
import {
  yarnFiltersFromSearch,
  yarnSearchFromFilters,
} from "./features/yarns/yarn-api";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("owner filter URL contract", () => {
  it("accepts me, shared and a positive user id, nothing else", () => {
    expect(ownerChoiceFromParam("me")).toBe("me");
    expect(ownerChoiceFromParam("mine")).toBe("me");
    expect(ownerChoiceFromParam("shared")).toBe("shared");
    expect(ownerChoiceFromParam("42")).toBe("42");
    for (const bad of [null, "", "0", "-4", "4.5", "abc", "1e3", "all"])
      expect(ownerChoiceFromParam(bad)).toBe("");
    expect(ownerChoiceIsUser("42")).toBe(true);
    expect(ownerChoiceIsUser("me")).toBe(false);
    expect(ownerChoiceIsUser("")).toBe(false);
  });

  it("round-trips ?owner= through the project and yarn list URLs", () => {
    for (const owner of ["me", "shared", "7"]) {
      const projects = filtersFromSearch(`?owner=${owner}&status=done`);
      expect(projects.owner).toBe(owner);
      expect(searchFromFilters(projects)).toBe(`status=done&owner=${owner}`);
      const yarns = yarnFiltersFromSearch(`?owner=${owner}`);
      expect(yarns.owner).toBe(owner);
      expect(yarnSearchFromFilters(yarns)).toBe(`owner=${owner}`);
    }
    expect(searchFromFilters(filtersFromSearch("?owner=bogus"))).toBe("");
  });
});

describe("listOwners", () => {
  it("asks the scoped owners route and keeps only valid owners", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: [
          { id: 3, email: "", name: "Anika", avatar_url: "/media/a.png" },
          { id: "x", email: "", name: "Broken" },
        ],
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const owners = await listOwners(
      "yarns",
      new AbortController().signal,
      "all",
    );
    expect(owners.map((owner) => owner.name)).toEqual(["Anika"]);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v2/yarns/owners?scope=all");
  });
});
