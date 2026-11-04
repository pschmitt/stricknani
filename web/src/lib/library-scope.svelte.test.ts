import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { scopeFor, setScope } from "./library-scope.svelte";

function stubStorage(initial: Record<string, string> = {}) {
  const store = new Map(Object.entries(initial));
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
  });
  return store;
}

const admin = { id: 11, is_admin: true };
const otherAdmin = { id: 12, is_admin: true };
const member = { id: 13, is_admin: false };

beforeEach(() => stubStorage());
afterEach(() => vi.unstubAllGlobals());

describe("Mine / Everyone scope", () => {
  it("defaults to Everyone for administrators", () => {
    expect(scopeFor(admin)).toBe("all");
  });

  it("is always Mine for everyone else, and a choice never sticks", () => {
    expect(scopeFor(member)).toBe("mine");
    expect(scopeFor(null)).toBe("mine");
    const store = stubStorage();
    setScope(member, "all");
    expect(scopeFor(member)).toBe("mine");
    expect(store.size).toBe(0);
  });

  it("remembers the choice per user id", () => {
    const store = stubStorage();
    setScope(admin, "mine");
    expect(scopeFor(admin)).toBe("mine");
    expect(scopeFor(otherAdmin)).toBe("all");
    expect([...store.keys()]).toEqual(["stricknani.library-scope.11"]);
  });

  it("reads a stored choice on the next visit", () => {
    stubStorage({ "stricknani.library-scope.12": "mine" });
    expect(scopeFor(otherAdmin)).toBe("mine");
  });

  it("works when storage throws", () => {
    vi.stubGlobal("localStorage", {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    });
    const blocked = { id: 99, is_admin: true };
    expect(scopeFor(blocked)).toBe("all");
    setScope(blocked, "mine");
    expect(scopeFor(blocked)).toBe("mine");
  });
});
