import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

function setup(stored: string | null, throwing = false) {
  const data = new Map<string, string>();
  if (stored !== null) data.set("stricknani.theme", stored);
  const attrs = new Map<string, string>();
  const metas = [{ content: "", setAttribute: vi.fn() }];
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => {
      if (throwing) throw new Error("denied");
      return data.get(k) ?? null;
    },
    setItem: (k: string, v: string) => {
      if (throwing) throw new Error("denied");
      data.set(k, v);
    },
    removeItem: (k: string) => void data.delete(k),
  });
  vi.stubGlobal("document", {
    documentElement: {
      setAttribute: (k: string, v: string) => attrs.set(k, v),
      removeAttribute: (k: string) => attrs.delete(k),
    },
    querySelectorAll: () => metas,
  });
  vi.stubGlobal("matchMedia", () => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  vi.stubGlobal("window", {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  return { data, attrs, metas };
}

describe("theme store", () => {
  beforeEach(() => vi.resetModules());
  afterEach(() => vi.unstubAllGlobals());

  it("applies the stored choice on init", async () => {
    const env = setup("dark");
    const { initTheme, theme } = await import("./theme.svelte");
    initTheme();
    expect(theme.choice).toBe("dark");
    expect(env.attrs.get("data-theme")).toBe("dark");
    expect(env.metas[0]?.setAttribute).toHaveBeenCalledWith(
      "content",
      "#1a1421",
    );
  });

  it("persists a change and drops the attribute for system", async () => {
    const env = setup(null);
    const { initTheme, setTheme, theme } = await import("./theme.svelte");
    initTheme();
    expect(theme.choice).toBe("system");
    setTheme("light");
    expect(env.data.get("stricknani.theme")).toBe("light");
    expect(env.attrs.get("data-theme")).toBe("light");
    setTheme("system");
    expect(env.data.has("stricknani.theme")).toBe(false);
    expect(env.attrs.has("data-theme")).toBe(false);
  });

  it("still switches for this visit when storage throws", async () => {
    const env = setup("dark", true);
    const { initTheme, setTheme, theme } = await import("./theme.svelte");
    initTheme();
    expect(theme.choice).toBe("system");
    setTheme("dark");
    expect(theme.choice).toBe("dark");
    expect(env.attrs.get("data-theme")).toBe("dark");
  });
});
