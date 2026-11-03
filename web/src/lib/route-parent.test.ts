import { describe, expect, it } from "vitest";
import { routeParent } from "./route-parent";

describe("routeParent", () => {
  it.each([
    ["/app/imports", "/app", "/app/create"],
    ["/app/imports/preview", "/app", "/app/imports"],
    ["/app/imports/preview/42", "/app", "/app/imports"],
    ["/app/projects/new", "/app", "/app/projects"],
    ["/app/projects/42", "/app", "/app/projects"],
    ["/app/projects/42/edit", "/app", "/app/projects/42"],
    ["/app/yarns/new", "/app", "/app/yarns"],
    ["/app/yarns/17", "/app", "/app/yarns"],
    ["/app/yarns/17/edit", "/app", "/app/yarns/17"],
    ["/app/admin/audit/123", "/app", "/app/admin/audit"],
    ["/app/admin/resources/projects/42", "/app", "/app/admin/audit"],
    ["/workspace/projects/42/edit", "/workspace/", "/workspace/projects/42"],
    ["/projects/42/edit", "", "/projects/42"],
  ])("maps %s under base %s to %s", (pathname, basePath, expected) => {
    expect(routeParent(pathname, basePath)).toBe(expected);
  });

  it.each([
    ["/app", "/app"],
    ["/app/projects", "/app"],
    ["/app/yarns", "/app"],
    ["/app/admin/audit", "/app"],
    ["/app/admin/resources/projects", "/app"],
    ["/app/unknown/42", "/app"],
    ["/application/projects/42", "/app"],
    ["/app/projects/42/unknown", "/app"],
  ])("has no parent for unmapped route %s", (pathname, basePath) => {
    expect(routeParent(pathname, basePath)).toBeNull();
  });
});
