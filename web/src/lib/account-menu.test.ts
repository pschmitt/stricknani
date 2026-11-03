import { describe, expect, it } from "vitest";
import {
  nextMenuIndex,
  shouldDismissSheet,
  trapTabIndex,
} from "./account-menu";

describe("account menu keyboard navigation", () => {
  it("wraps arrows and jumps with Home and End", () => {
    expect(nextMenuIndex(0, 3, "ArrowDown")).toBe(1);
    expect(nextMenuIndex(2, 3, "ArrowDown")).toBe(0);
    expect(nextMenuIndex(0, 3, "ArrowUp")).toBe(2);
    expect(nextMenuIndex(1, 3, "Home")).toBe(0);
    expect(nextMenuIndex(1, 3, "End")).toBe(2);
  });

  it("starts from the ends when nothing is focused and ignores other keys", () => {
    expect(nextMenuIndex(-1, 3, "ArrowDown")).toBe(0);
    expect(nextMenuIndex(-1, 3, "ArrowUp")).toBe(2);
    expect(nextMenuIndex(0, 3, "a")).toBeNull();
    expect(nextMenuIndex(0, 0, "ArrowDown")).toBeNull();
  });
});

describe("sheet swipe dismissal", () => {
  it("dismisses on a long drag or a quick flick down", () => {
    expect(shouldDismissSheet(120, 600)).toBe(true);
    expect(shouldDismissSheet(40, 50)).toBe(true);
  });

  it("snaps back on short slow drags and upward drags", () => {
    expect(shouldDismissSheet(40, 600)).toBe(false);
    expect(shouldDismissSheet(10, 5)).toBe(false);
    expect(shouldDismissSheet(-200, 100)).toBe(false);
  });
});

describe("focus trap", () => {
  it("wraps Tab at both ends and otherwise leaves the browser alone", () => {
    expect(trapTabIndex(2, 3, false)).toBe(0);
    expect(trapTabIndex(0, 3, true)).toBe(2);
    expect(trapTabIndex(1, 3, false)).toBeNull();
    expect(trapTabIndex(-1, 3, false)).toBe(0);
    expect(trapTabIndex(0, 0, false)).toBeNull();
  });
});
