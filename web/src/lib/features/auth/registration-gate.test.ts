import { describe, expect, it } from "vitest";
import { registrationOpen, selfHostCardVisible } from "./registration-gate";

const caps = (signup: boolean) => ({ signup }) as never;
const anonymous = (signup: boolean) => ({
  status: "ready" as const,
  data: { user: null, capabilities: caps(signup) },
});

describe("registration gating", () => {
  it("shows the sign-up path and hides the card when registration is open", () => {
    expect(registrationOpen(anonymous(true))).toBe(true);
    expect(selfHostCardVisible(anonymous(true))).toBe(false);
  });

  it("shows the card and hides the sign-up path when registration is closed", () => {
    expect(registrationOpen(anonymous(false))).toBe(false);
    expect(selfHostCardVisible(anonymous(false))).toBe(true);
  });

  it("shows neither while the session is loading or failed", () => {
    for (const status of ["loading", "error"] as const) {
      expect(registrationOpen({ status, data: null })).toBe(false);
      expect(selfHostCardVisible({ status, data: null })).toBe(false);
    }
  });

  it("shows neither to a signed-in user", () => {
    const signedIn = {
      status: "ready" as const,
      data: { user: { id: 1 } as never, capabilities: caps(false) },
    };
    expect(registrationOpen(signedIn)).toBe(false);
    expect(selfHostCardVisible(signedIn)).toBe(false);
  });
});
