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
});
