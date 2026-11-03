import { describe, expect, it, vi } from "vitest";
import {
  DEVELOPER_STORAGE_KEY,
  TapCounter,
  readDeveloperMode,
  tapMessage,
  writeDeveloperMode,
} from "#lib/developer-mode.ts";

describe("tap counter", () => {
  it("is silent twice, counts down from four, then unlocks", () => {
    const counter = new TapCounter();
    const results = Array.from({ length: 7 }, (_, i) =>
      counter.tap(i * 100, false),
    );
    expect(results.map((r) => tapMessage(r))).toEqual([
      null,
      null,
      "You are now 4 steps away from being a developer.",
      "You are now 3 steps away from being a developer.",
      "You are now 2 steps away from being a developer.",
      "You are now 1 step away from being a developer.",
      "You are now a developer!",
    ]);
    expect(results[6]).toEqual({ kind: "unlock" });
  });

  it("starts over after a pause of more than two seconds", () => {
    const counter = new TapCounter();
    for (let i = 0; i < 4; i++) counter.tap(i * 100, false);
    expect(counter.tap(2_400, false)).toEqual({ kind: "silent" });
    expect(counter.tap(2_500, false)).toEqual({ kind: "silent" });
    expect(counter.tap(2_600, false)).toEqual({
      kind: "countdown",
      stepsLeft: 4,
    });
  });

  it("keeps counting when taps are exactly two seconds apart", () => {
    const counter = new TapCounter();
    let last;
    for (let i = 1; i <= 7; i++) last = counter.tap(i * 2_000, false);
    expect(last).toEqual({ kind: "unlock" });
  });

  it("answers an existing developer without counting", () => {
    const counter = new TapCounter();
    const result = counter.tap(0, true);
    expect(tapMessage(result)).toBe("No need, you are already a developer.");
  });

  it("restarts after an unlock and after reset", () => {
    const counter = new TapCounter();
    for (let i = 0; i < 7; i++) counter.tap(i * 50, false);
    expect(counter.tap(400, false)).toEqual({ kind: "silent" });
    counter.reset();
    counter.tap(450, false);
    counter.tap(500, false);
    expect(counter.tap(550, false)).toEqual({
      kind: "countdown",
      stepsLeft: 4,
    });
  });
});

describe("developer mode storage", () => {
  it("round-trips through localStorage", () => {
    const store = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    });
    expect(readDeveloperMode()).toBe(false);
    writeDeveloperMode(true);
    expect(store.get(DEVELOPER_STORAGE_KEY)).toBe("1");
    expect(readDeveloperMode()).toBe(true);
    writeDeveloperMode(false);
    expect(readDeveloperMode()).toBe(false);
    vi.unstubAllGlobals();
  });

  it("survives storage that throws", () => {
    vi.stubGlobal("localStorage", {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
      removeItem: () => {
        throw new Error("blocked");
      },
    });
    expect(readDeveloperMode()).toBe(false);
    expect(() => writeDeveloperMode(true)).not.toThrow();
    vi.unstubAllGlobals();
  });
});
