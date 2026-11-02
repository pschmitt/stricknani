import { describe, expect, it } from "vitest";
import {
  hasInternalRouteBack,
  internalRouteBackTarget,
  parentRouteFallback,
  resetInternalRouteStack,
  shouldRedirectExpiredSession,
  showPublicGauge,
  trackInternalRoute,
} from "./app-shell";

describe("anonymous public gauge shell", () => {
  const pathname = "/app/gauge";
  const gaugePath = "/app/gauge";
  const loginPath = "/app/login";

  it("waits for session bootstrap before rendering the public tool", () => {
    expect(showPublicGauge(pathname, gaugePath, "loading", false)).toBe(false);
    expect(showPublicGauge(pathname, gaugePath, "ready", false)).toBe(true);
  });

  it("keeps the stateless gauge available after a private session expires", () => {
    const isPublicGauge = showPublicGauge(pathname, gaugePath, "ready", false);
    expect(
      shouldRedirectExpiredSession(
        true,
        "ready",
        pathname,
        loginPath,
        isPublicGauge,
      ),
    ).toBe(false);
  });

  it("still redirects expired sessions from protected routes", () => {
    expect(
      shouldRedirectExpiredSession(
        true,
        "ready",
        "/app/projects",
        loginPath,
        false,
      ),
    ).toBe(true);
  });
});

describe("application back navigation", () => {
  const routes = {
    projects: "/app/projects",
    yarns: "/app/yarns",
    more: "/app/more",
    imports: "/app/imports",
    categories: "/app/categories",
    adminAudit: "/app/admin/audit",
  };

  it("uses a tracked app route and clears it when browser back reaches the start", () => {
    resetInternalRouteStack();
    trackInternalRoute("/app/projects", "enter");
    trackInternalRoute("/app/yarns", "link");
    expect(hasInternalRouteBack("/app/yarns")).toBe(true);
    expect(internalRouteBackTarget("/app/yarns")).toBe("/app/projects");

    trackInternalRoute("/app/projects", "popstate");
    expect(hasInternalRouteBack("/app/projects")).toBe(false);
    expect(internalRouteBackTarget("/app/projects")).toBeNull();
  });

  it("does not add query-only navigation to the route stack", () => {
    resetInternalRouteStack();
    trackInternalRoute("/app/projects", "enter");
    trackInternalRoute("/app/projects", "goto");
    expect(hasInternalRouteBack("/app/projects")).toBe(false);
  });

  it("uses a parent route for direct project, yarn, and admin entries", () => {
    expect(parentRouteFallback("/app/projects/42/edit", routes)).toBe(
      "/app/projects/42",
    );
    expect(parentRouteFallback("/app/projects/42", routes)).toBe(
      "/app/projects",
    );
    expect(parentRouteFallback("/app/yarns/9/edit", routes)).toBe(
      "/app/yarns/9",
    );
    expect(parentRouteFallback("/app/yarns/9", routes)).toBe("/app/yarns");
    expect(parentRouteFallback("/app/admin/resources/project/42", routes)).toBe(
      "/app/admin/audit",
    );
    expect(parentRouteFallback("/app/admin/audit", routes)).toBe("/app/more");
    expect(parentRouteFallback("/app/admin/users", routes)).toBe("/app/more");
    expect(parentRouteFallback("/app/imports", routes)).toBe("/app/more");
    expect(parentRouteFallback("/app/categories", routes)).toBe("/app/more");
  });

  it("resets when browser history moves to an untracked app route", () => {
    resetInternalRouteStack();
    trackInternalRoute("/app/projects", "enter");
    trackInternalRoute("/app/yarns", "link");
    trackInternalRoute("/app/account", "popstate");
    expect(hasInternalRouteBack("/app/account")).toBe(false);
    expect(internalRouteBackTarget("/app/account")).toBeNull();
  });
});
