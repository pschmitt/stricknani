// Unit checks for the read-only administrator browser journey helpers.
import type { Page } from "@playwright/test";
import { describe, expect, it } from "vitest";

import { assertNoHorizontalOverflow } from "../journeys/admin";
import { AcceptanceFailure, loopbackOrigin } from "./common";

describe("administrator responsive guard", () => {
  it.each([
    "resource",
    "diff",
    "project-reader",
    "project-library",
    "yarn-library",
  ])("accepts the %s read-only route", async (route) => {
    const page = {
      evaluate: async () => ({
        viewport: 320,
        documentClientWidth: 320,
        documentScrollWidth: 320,
        offenders: [],
      }),
    } as unknown as Page;

    await expect(
      assertNoHorizontalOverflow(page, route),
    ).resolves.toBeUndefined();
  });

  it("rejects unknown routes", async () => {
    const page = {} as unknown as Page;

    await expect(
      assertNoHorizontalOverflow(page, "unknown"),
    ).rejects.toBeInstanceOf(AcceptanceFailure);
  });

  it("rejects a page that overflows its viewport", async () => {
    const page = {
      evaluate: async () => ({
        viewport: 320,
        documentClientWidth: 320,
        documentScrollWidth: 400,
        offenders: [],
      }),
    } as unknown as Page;

    await expect(
      assertNoHorizontalOverflow(page, "resource"),
    ).rejects.toBeInstanceOf(AcceptanceFailure);
  });
});

describe("administrator fixture origin", () => {
  it("rejects a non-loopback URL", () => {
    expect(() => loopbackOrigin("http://example.invalid:8080")).toThrow(
      AcceptanceFailure,
    );
  });
});
