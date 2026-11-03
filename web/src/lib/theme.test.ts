import { describe, expect, it, vi } from "vitest";
import {
  THEME_STORAGE_KEY,
  applyTheme,
  parseTheme,
  readTheme,
  themeColorFor,
  themeColors,
  writeTheme,
} from "./theme";

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
    data,
  };
}

const throwingStorage = {
  getItem: () => {
    throw new Error("denied");
  },
  setItem: () => {
    throw new Error("denied");
  },
  removeItem: () => {
    throw new Error("denied");
  },
};

describe("theme storage", () => {
  it("defaults to system", () => {
    expect(readTheme(memoryStorage())).toBe("system");
    expect(readTheme(null)).toBe("system");
  });

  it("persists light and dark and clears the key for system", () => {
    const storage = memoryStorage();
    expect(writeTheme("dark", storage)).toBe(true);
    expect(storage.data.get(THEME_STORAGE_KEY)).toBe("dark");
    expect(readTheme(storage)).toBe("dark");
    writeTheme("light", storage);
    expect(readTheme(storage)).toBe("light");
    writeTheme("system", storage);
    expect(storage.data.has(THEME_STORAGE_KEY)).toBe(false);
  });

  it("falls back to system on a corrupt value", () => {
    expect(readTheme(memoryStorage({ [THEME_STORAGE_KEY]: "purple" }))).toBe(
      "system",
    );
    expect(parseTheme(42)).toBe("system");
    expect(parseTheme(null)).toBe("system");
  });

  it("survives storage that throws", () => {
    expect(readTheme(throwingStorage)).toBe("system");
    expect(writeTheme("dark", throwingStorage)).toBe(false);
    expect(writeTheme("dark", null)).toBe(false);
  });
});

describe("theme application", () => {
  it("sets data-theme for light and dark and removes it for system", () => {
    const root = { setAttribute: vi.fn(), removeAttribute: vi.fn() };
    applyTheme("dark", root);
    expect(root.setAttribute).toHaveBeenLastCalledWith("data-theme", "dark");
    applyTheme("light", root);
    expect(root.setAttribute).toHaveBeenLastCalledWith("data-theme", "light");
    applyTheme("system", root);
    expect(root.removeAttribute).toHaveBeenCalledWith("data-theme");
  });

  it("picks the browser chrome colour from the choice or the OS", () => {
    expect(themeColorFor("dark", false)).toBe(themeColors.dark);
    expect(themeColorFor("light", true)).toBe(themeColors.light);
    expect(themeColorFor("system", true)).toBe(themeColors.dark);
    expect(themeColorFor("system", false)).toBe(themeColors.light);
  });
});
