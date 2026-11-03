import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEXT_SIZE_STORAGE_KEY, textSize } from "./text-size.svelte";

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
    data,
  };
}

describe("textSize", () => {
  beforeEach(() => {
    vi.stubGlobal("localStorage", memoryStorage());
    textSize.reload();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("defaults to 100%", () => {
    expect(textSize.percent).toBe(100);
    expect(textSize.label).toBe("100%");
    expect(textSize.cssClass).toBe("reading-medium");
    expect(textSize.isDefault).toBe(true);
  });

  it("steps up and down and clamps at the ends", () => {
    textSize.up();
    expect(textSize.percent).toBe(115);
    expect(textSize.isMax).toBe(true);
    textSize.up();
    expect(textSize.percent).toBe(115);
    textSize.down();
    textSize.down();
    expect(textSize.percent).toBe(90);
    expect(textSize.isMin).toBe(true);
    textSize.down();
    expect(textSize.percent).toBe(90);
    textSize.reset();
    expect(textSize.percent).toBe(100);
  });

  it("persists and restores the value", () => {
    textSize.up();
    expect(localStorage.getItem(TEXT_SIZE_STORAGE_KEY)).toBe("115");
    textSize.reset();
    textSize.reload();
    expect(textSize.percent).toBe(100);
    textSize.down();
    textSize.reload();
    expect(textSize.percent).toBe(90);
  });

  it.each(["abc", "120", "", "1", "NaN"])(
    "falls back to 100% for corrupt value %j",
    (raw) => {
      vi.stubGlobal(
        "localStorage",
        memoryStorage({ [TEXT_SIZE_STORAGE_KEY]: raw }),
      );
      textSize.reload();
      expect(textSize.percent).toBe(100);
    },
  );

  it("survives storage that throws", () => {
    const boom = () => {
      throw new Error("blocked");
    };
    vi.stubGlobal("localStorage", {
      getItem: boom,
      setItem: boom,
      removeItem: boom,
    });
    expect(() => textSize.reload()).not.toThrow();
    expect(textSize.percent).toBe(100);
    expect(() => textSize.up()).not.toThrow();
    expect(textSize.percent).toBe(115);
  });

  it("works without localStorage", () => {
    vi.stubGlobal("localStorage", undefined);
    expect(() => textSize.reload()).not.toThrow();
    expect(() => textSize.down()).not.toThrow();
    expect(textSize.percent).toBe(90);
  });
});
