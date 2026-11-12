import { describe, expect, it } from "vitest";
import {
  DATA_EPOCH_KEY,
  applyDataEpoch,
  compareEpoch,
  discardDataBoundState,
  fetchDataEpoch,
  type EpochStorage,
} from "./data-epoch";

function memoryStorage(initial: Record<string, string> = {}): EpochStorage {
  const map = new Map(Object.entries(initial));
  return {
    get length() {
      return map.size;
    },
    key: (index) => [...map.keys()][index] ?? null,
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  };
}

describe("data epoch", () => {
  it("compares the stored and the current epoch", () => {
    expect(compareEpoch(null, "a")).toBe("first");
    expect(compareEpoch("a", "a")).toBe("unchanged");
    expect(compareEpoch("a", "b")).toBe("changed");
    expect(compareEpoch("a", null)).toBe("unchanged");
    expect(compareEpoch("a", "")).toBe("unchanged");
  });

  it("remembers the first epoch without discarding anything", () => {
    const storage = memoryStorage({ "stricknani.projectTimers.1": "x" });
    expect(applyDataEpoch(storage, "one")).toBe("first");
    expect(storage.getItem(DATA_EPOCH_KEY)).toBe("one");
    expect(storage.getItem("stricknani.projectTimers.1")).toBe("x");
  });

  it("drops data-bound state after a restore but keeps preferences", () => {
    const storage = memoryStorage({
      [DATA_EPOCH_KEY]: "one",
      "stricknani:timer-reminder:4:9": "{}",
      "stricknani.library-scope.4": "all",
      "stricknani.projectTimers.9": "{}",
      "stricknani.theme": "dark",
      "stricknani.locale": "de",
    });
    expect(applyDataEpoch(storage, "two")).toBe("changed");
    expect(storage.getItem(DATA_EPOCH_KEY)).toBe("two");
    expect(storage.getItem("stricknani:timer-reminder:4:9")).toBeNull();
    expect(storage.getItem("stricknani.library-scope.4")).toBeNull();
    expect(storage.getItem("stricknani.projectTimers.9")).toBeNull();
    expect(storage.getItem("stricknani.theme")).toBe("dark");
    expect(storage.getItem("stricknani.locale")).toBe("de");
    expect(discardDataBoundState(storage)).toBe(0);
  });

  it("does nothing without storage or an epoch", () => {
    expect(applyDataEpoch(undefined, "x")).toBe("unchanged");
    const storage = memoryStorage();
    expect(applyDataEpoch(storage, null)).toBe("unchanged");
    expect(storage.getItem(DATA_EPOCH_KEY)).toBeNull();
  });

  it("reads the epoch from the meta endpoint and ignores bad answers", async () => {
    const answer = (body: unknown, ok = true) =>
      (async () => ({ ok, json: async () => body })) as unknown as typeof fetch;
    expect(await fetchDataEpoch(answer({ data: { data_epoch: "abc" } }))).toBe(
      "abc",
    );
    expect(await fetchDataEpoch(answer({ data: {} }))).toBeNull();
    expect(
      await fetchDataEpoch(answer({ data: { data_epoch: 4 } })),
    ).toBeNull();
    expect(await fetchDataEpoch(answer({}, false))).toBeNull();
    expect(
      await fetchDataEpoch((async () => {
        throw new Error("offline");
      }) as unknown as typeof fetch),
    ).toBeNull();
  });
});
