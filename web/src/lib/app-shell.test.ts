import { describe, expect, it } from "vitest";
import { shouldRedirectExpiredSession, showPublicGauge } from "./app-shell";

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

  it("never redirects an anonymous visitor away from public routes", () => {
    for (const route of ["/app/login", "/app/signup", "/app/gauge"]) {
      const isPublicGauge = showPublicGauge(route, gaugePath, "ready", false);
      expect(
        shouldRedirectExpiredSession(
          true,
          "ready",
          route,
          loginPath,
          isPublicGauge,
          "/app/signup",
        ),
      ).toBe(false);
    }
  });

  it("does not redirect without an expiry, even on protected routes", () => {
    expect(
      shouldRedirectExpiredSession(
        false,
        "ready",
        "/app/projects",
        loginPath,
        false,
      ),
    ).toBe(false);
  });

  it("waits for the session to settle before redirecting", () => {
    expect(
      shouldRedirectExpiredSession(
        true,
        "loading",
        "/app/projects",
        loginPath,
        false,
      ),
    ).toBe(false);
  });

  it("allows the public gauge after logout, but still guards protected routes", () => {
    // logout leaves an anonymous, ready session; an in-flight 401 may have set expired
    expect(
      shouldRedirectExpiredSession(
        true,
        "ready",
        gaugePath,
        loginPath,
        showPublicGauge(gaugePath, gaugePath, "ready", false),
        "/app/signup",
      ),
    ).toBe(false);
    expect(
      shouldRedirectExpiredSession(
        true,
        "ready",
        "/app/projects",
        loginPath,
        false,
        "/app/signup",
      ),
    ).toBe(true);
  });
});
