import { afterEach, describe, expect, it, vi } from "vitest";
import { searchOwnStash } from "./stash-search";

afterEach(() => vi.unstubAllGlobals());

describe("import stash search", () => {
  it("asks for the user's own yarns only, never everyone's", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: [],
        meta: { page: 1, per_page: 50, has_more: false, scope: "mine" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    await searchOwnStash(" merino ", new AbortController().signal);
    const url = new URL(String(fetchMock.mock.calls[0]?.[0]), "http://x");
    expect(url.pathname).toBe("/api/v2/yarns");
    expect(url.searchParams.get("scope")).toBe("mine");
    expect(url.searchParams.get("q")).toBe("merino");
    expect(url.searchParams.has("owner_id")).toBe(false);
  });
});
