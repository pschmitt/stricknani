// Unit checks for the focused accessibility journey's keyboard helper.
import type { Locator, Page } from "@playwright/test";
import { describe, expect, it, vi } from "vitest";

import { tabTo } from "../journeys/accessibility";

function fakePage(press: ReturnType<typeof vi.fn>): Page {
  return { keyboard: { press } } as unknown as Page;
}

describe("tabTo", () => {
  it("reaches the target with keyboard events", async () => {
    const press = vi.fn().mockResolvedValue(undefined);
    const evaluate = vi
      .fn()
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true);
    const locator = { evaluate } as unknown as Locator;

    await tabTo(fakePage(press), locator, "target field");

    expect(press.mock.calls).toEqual([["Tab"], ["Tab"]]);
  });

  it("does not advance when the target is already focused", async () => {
    const press = vi.fn().mockResolvedValue(undefined);
    const locator = {
      evaluate: vi.fn().mockResolvedValue(true),
    } as unknown as Locator;

    await tabTo(fakePage(press), locator, "target field");

    expect(press).not.toHaveBeenCalled();
  });

  it("fails with a sanitized message when the target is unreachable", async () => {
    const press = vi.fn().mockResolvedValue(undefined);
    const locator = {
      evaluate: vi.fn().mockResolvedValue(false),
    } as unknown as Locator;

    await expect(
      tabTo(fakePage(press), locator, "target field"),
    ).rejects.toThrow("keyboard navigation could not reach target field");
    expect(press).toHaveBeenCalledTimes(80);
  });
});
