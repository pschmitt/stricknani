import { describe, expect, it } from "vitest";
import { notificationState, safeAppPath } from "./web-notifications.svelte.ts";

describe("safeAppPath", () => {
  it("keeps same-origin app paths with their query", () => {
    expect(safeAppPath("/app/projects/3")).toBe("/app/projects/3");
    expect(safeAppPath("/app/yarns?scope=all")).toBe("/app/yarns?scope=all");
  });

  it("falls back to the app start for anything else", () => {
    expect(safeAppPath("https://example.com/app/projects/3")).toBe("/app/");
    expect(safeAppPath("//example.com/app/")).toBe("/app/");
    expect(safeAppPath("/api/v2/projects")).toBe("/app/");
    expect(safeAppPath("/app/../api/v2/projects")).toBe("/app/");
    expect(safeAppPath(undefined)).toBe("/app/");
    expect(safeAppPath(42)).toBe("/app/");
  });
});

describe("notificationState", () => {
  it("is on only with permission and the switch on", () => {
    expect(notificationState(true, "granted", true)).toBe("on");
    expect(notificationState(true, "granted", false)).toBe("off");
    expect(notificationState(true, "default", true)).toBe("off");
  });

  it("reports blocked and unsupported browsers", () => {
    expect(notificationState(true, "denied", true)).toBe("denied");
    expect(notificationState(false, null, true)).toBe("unsupported");
    expect(notificationState(true, null, false)).toBe("unsupported");
  });
});
