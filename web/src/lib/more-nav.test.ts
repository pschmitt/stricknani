import { describe, expect, it } from "vitest";
import { isMoreActive, isPathActive } from "./more-nav";

const base = (path: string) => `/app${path}`;

describe("isPathActive", () => {
  it("matches the path and its children, not siblings", () => {
    expect(isPathActive("/app/projects", "/app/projects")).toBe(true);
    expect(isPathActive("/app/projects/4", "/app/projects")).toBe(true);
    expect(isPathActive("/app/projects-x", "/app/projects")).toBe(false);
  });
});

describe("isMoreActive", () => {
  it.each([
    "/app/more",
    "/app/create",
    "/app/imports",
    "/app/categories",
    "/app/categories/3",
    "/app/gauge",
    "/app/trash",
    "/app/admin/users",
    "/app/admin/audit",
    "/app/about",
    "/app/account",
    "/app/delete-account",
    "/app/account/",
  ])("is active on %s", (pathname) => {
    expect(isMoreActive(pathname, base)).toBe(true);
  });

  it.each([
    "/app",
    "/app/",
    "/app/projects",
    "/app/projects/new",
    "/app/yarns/2",
  ])("is not active on %s", (pathname) => {
    expect(isMoreActive(pathname, base)).toBe(false);
  });
});
