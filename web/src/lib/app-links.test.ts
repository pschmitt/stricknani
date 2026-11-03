import { afterEach, describe, expect, it, vi } from "vitest";
import {
  androidAction,
  androidDownloadUrl,
  appCardDismissedKey,
  appCardMode,
  isStandaloneDisplay,
  readAppCardDismissed,
  writeAppCardDismissed,
} from "./app-links";

const android = "Mozilla/5.0 (Linux; Android 15; Pixel 9) Chrome/130.0 Mobile";
const desktop = "Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0";

afterEach(() => vi.unstubAllGlobals());

describe("appCardMode", () => {
  it("is prominent in an Android browser", () => {
    expect(
      appCardMode({ userAgent: android, standalone: false, dismissed: false }),
    ).toBe("prominent");
  });

  it("is compact on other platforms", () => {
    expect(
      appCardMode({ userAgent: desktop, standalone: false, dismissed: false }),
    ).toBe("compact");
  });

  it("is hidden once dismissed", () => {
    for (const userAgent of [android, desktop]) {
      expect(
        appCardMode({ userAgent, standalone: false, dismissed: true }),
      ).toBe("hidden");
    }
  });

  it("is hidden in an installed Android PWA but not on a desktop one", () => {
    expect(
      appCardMode({ userAgent: android, standalone: true, dismissed: false }),
    ).toBe("hidden");
    expect(
      appCardMode({ userAgent: desktop, standalone: true, dismissed: false }),
    ).toBe("compact");
  });
});

describe("androidAction", () => {
  it("points at the GitHub releases until a Play listing is set", () => {
    expect(androidAction(null)).toEqual({
      kind: "apk",
      url: androidDownloadUrl,
    });
  });

  it("prefers the Play listing when one is set", () => {
    expect(androidAction("https://play.google.com/store/apps/x")).toEqual({
      kind: "play",
      url: "https://play.google.com/store/apps/x",
    });
  });
});

describe("dismissal storage", () => {
  it("round-trips through localStorage", () => {
    const store = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, value),
    });
    expect(readAppCardDismissed()).toBe(false);
    writeAppCardDismissed();
    expect(store.get(appCardDismissedKey)).toBe("1");
    expect(readAppCardDismissed()).toBe(true);
  });

  it("survives blocked storage", () => {
    vi.stubGlobal("localStorage", {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    });
    expect(readAppCardDismissed()).toBe(false);
    expect(() => writeAppCardDismissed()).not.toThrow();
  });
});

describe("isStandaloneDisplay", () => {
  it("reads the display-mode media query", () => {
    vi.stubGlobal("matchMedia", (query: string) => ({
      matches: query === "(display-mode: standalone)",
    }));
    expect(isStandaloneDisplay()).toBe(true);
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    expect(isStandaloneDisplay()).toBe(false);
  });
});
