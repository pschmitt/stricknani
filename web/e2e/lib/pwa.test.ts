import { describe, expect, it } from "vitest";

import { AcceptanceFailure } from "./common";
import {
  countOccurrences,
  expandWorker,
  pwaVersion,
  setsEqual,
  sha256Hex,
  waitForAsyncCondition,
} from "./pwa";

/** Mirror of the Python `PollingPage`: scripted results, then `false`. */
class PollingPage {
  evaluations = 0;

  constructor(private readonly results: boolean[]) {}

  poll = async (): Promise<boolean> => {
    this.evaluations += 1;
    return this.results.shift() ?? false;
  };

  async waitForTimeout(): Promise<void> {}
}

describe("waitForAsyncCondition", () => {
  it("times out on a condition that never resolves true", async () => {
    const page = new PollingPage([false]);

    await expect(
      waitForAsyncCondition(page, page.poll, "worker activation", 0),
    ).rejects.toThrow(/timed out waiting/);
    await expect(
      waitForAsyncCondition(page, page.poll, "worker activation", 0),
    ).rejects.toBeInstanceOf(AcceptanceFailure);

    // One evaluation for the first call, one more for the second.
    expect(page.evaluations).toBe(2);
  });

  it("re-polls until the condition resolves true", async () => {
    const page = new PollingPage([false, false, true]);

    await waitForAsyncCondition(page, page.poll, "worker activation", 1_000);

    expect(page.evaluations).toBe(3);
  });

  it("does not treat truthy non-boolean results as success", async () => {
    let calls = 0;
    const page = { waitForTimeout: async () => {} };

    await expect(
      waitForAsyncCondition(
        page,
        async () => {
          calls += 1;
          return "yes";
        },
        "a truthy string",
        0,
      ),
    ).rejects.toThrow(/timed out waiting for a truthy string/);
    expect(calls).toBe(1);
  });
});

describe("worker versioning", () => {
  it("expands the worker markers literally", () => {
    const template =
      'const V = "__APP_VERSION__"; const C = "__APP_CODE_VERSION__"; ' +
      "const A = __APP_ASSETS__;";

    const worker = expandWorker(template, "$&v", "code$1", ["/app/a.js"]);

    expect(worker).toBe(
      'const V = "$&v"; const C = "code$1"; const A = ["/app/a.js"];',
    );
    expect(worker).not.toContain("__APP_");
  });

  it("hashes the code version, the asset digests and the worker", () => {
    const digests = [
      ["/app/a.js", sha256Hex("a")],
      ["/app/b.css", sha256Hex("b")],
    ] as const;

    const expected = sha256Hex(
      `v1\0/app/a.js\0${sha256Hex("a")}\n/app/b.css\0${sha256Hex("b")}\n\0worker`,
    );

    expect(pwaVersion("v1", digests, "worker")).toBe(expected);
    expect(pwaVersion("v2", digests, "worker")).not.toBe(expected);
  });
});

describe("set helpers", () => {
  it("compares sets and counts occurrences", () => {
    expect(setsEqual(new Set(["a", "b"]), new Set(["b", "a"]))).toBe(true);
    expect(setsEqual(new Set(["a"]), new Set(["a", "b"]))).toBe(false);
    expect(countOccurrences("skip(); skip(); skip", "skip()")).toBe(2);
  });
});
