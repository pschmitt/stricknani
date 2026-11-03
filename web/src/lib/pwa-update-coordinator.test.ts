import { afterEach, describe, expect, it, vi } from "vitest";

const pending = vi.hoisted(() => ({ value: false }));
vi.mock("#lib/api.ts", () => ({
  hasPendingApiMutations: () => pending.value,
}));

import {
  registerPwaUpdateGuard,
  updateReadiness,
} from "#lib/pwa-update-coordinator.ts";

describe("updateReadiness", () => {
  const cleanups: Array<() => void> = [];
  afterEach(() => {
    while (cleanups.length) cleanups.pop()?.();
    pending.value = false;
  });

  it("is ready without guards or pending writes", () => {
    expect(updateReadiness()).toBe("ready");
  });

  it("reports a dirty form", () => {
    cleanups.push(registerPwaUpdateGuard(() => ({ dirty: true, busy: false })));
    expect(updateReadiness()).toBe("dirty");
  });

  it("reports a running action as busy", () => {
    cleanups.push(registerPwaUpdateGuard(() => ({ dirty: true, busy: true })));
    expect(updateReadiness()).toBe("busy");
  });

  it("treats a throwing guard as busy", () => {
    cleanups.push(
      registerPwaUpdateGuard(() => {
        throw new Error("gone");
      }),
    );
    expect(updateReadiness()).toBe("busy");
  });

  it("reports pending API writes as busy", () => {
    pending.value = true;
    expect(updateReadiness()).toBe("busy");
  });

  it("forgets a guard after it unregisters", () => {
    const unregister = registerPwaUpdateGuard(() => ({
      dirty: true,
      busy: false,
    }));
    unregister();
    expect(updateReadiness()).toBe("ready");
  });
});
