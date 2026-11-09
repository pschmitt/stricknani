import { describe, expect, it } from "vitest";
import {
  EDITOR_MODE_STORAGE_KEY,
  readEditorMode,
  writeEditorMode,
} from "./editor-mode.ts";

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
  };
}

describe("editor mode persistence", () => {
  it("defaults to the visual editor", () => {
    expect(readEditorMode(memoryStorage())).toBe("visual");
    expect(readEditorMode(null)).toBe("visual");
  });

  it("remembers raw and visual", () => {
    const storage = memoryStorage();
    writeEditorMode("raw", storage);
    expect(storage.getItem(EDITOR_MODE_STORAGE_KEY)).toBe("raw");
    expect(readEditorMode(storage)).toBe("raw");
    writeEditorMode("visual", storage);
    expect(readEditorMode(storage)).toBe("visual");
  });

  it("ignores unknown stored values", () => {
    expect(
      readEditorMode(memoryStorage({ [EDITOR_MODE_STORAGE_KEY]: "wat" })),
    ).toBe("visual");
  });

  it("survives storage that throws", () => {
    const broken = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    };
    expect(readEditorMode(broken)).toBe("visual");
    expect(() => writeEditorMode("raw", broken)).not.toThrow();
  });
});
