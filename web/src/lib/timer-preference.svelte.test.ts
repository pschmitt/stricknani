import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  };
}

describe("project timer browser preference", () => {
  beforeEach(() => vi.resetModules());
  afterEach(() => vi.unstubAllGlobals());

  it("defaults to enabled for each account", async () => {
    vi.stubGlobal("localStorage", memoryStorage());
    const { projectTimerPreference } =
      await import("./timer-preference.svelte");
    expect(projectTimerPreference.isEnabled(12)).toBe(true);
    expect(projectTimerPreference.isEnabled(13)).toBe(true);
    expect(projectTimerPreference.isEnabled(null)).toBe(true);
  });

  it("persists independently by account and removes the default value", async () => {
    const stored = memoryStorage();
    vi.stubGlobal("localStorage", stored);
    const { PROJECT_TIMERS_STORAGE_PREFIX, projectTimerPreference } =
      await import("./timer-preference.svelte");
    const key = `${PROJECT_TIMERS_STORAGE_PREFIX}12`;

    projectTimerPreference.setEnabled(12, false);
    expect(stored.values.get(key)).toBe("0");
    expect(projectTimerPreference.isEnabled(12)).toBe(false);
    expect(projectTimerPreference.isEnabled(13)).toBe(true);

    projectTimerPreference.setEnabled(12, true);
    expect(stored.values.has(key)).toBe(false);
    expect(projectTimerPreference.isEnabled(12)).toBe(true);
  });

  it("re-reads changes from another browser tab", async () => {
    const stored = memoryStorage();
    const addEventListener = vi.fn();
    vi.stubGlobal("localStorage", stored);
    vi.stubGlobal("window", { addEventListener });
    const { PROJECT_TIMERS_STORAGE_PREFIX, projectTimerPreference } =
      await import("./timer-preference.svelte");
    const listener = addEventListener.mock.calls.find(
      ([type]) => type === "storage",
    )?.[1] as ((event: StorageEvent) => void) | undefined;
    expect(listener).toBeDefined();
    projectTimerPreference.isEnabled(12);
    listener?.({
      key: `${PROJECT_TIMERS_STORAGE_PREFIX}12`,
      newValue: "0",
    } as StorageEvent);
    expect(projectTimerPreference.isEnabled(12)).toBe(false);
  });

  it("keeps the current-visit setting when storage is unavailable", async () => {
    vi.stubGlobal("localStorage", undefined);
    const { projectTimerPreference } =
      await import("./timer-preference.svelte");
    projectTimerPreference.setEnabled(12, false);
    expect(projectTimerPreference.isEnabled(12)).toBe(false);
  });
});
