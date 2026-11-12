import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { describe, expect, it } from "vitest";

const workerTemplate = readFileSync(
  new URL("../../static/service-worker.js", import.meta.url),
  "utf8",
);

class MockResponse {
  cancelled = false;
  readonly body = {
    cancel: async () => {
      this.cancelled = true;
    },
  };

  constructor(private readonly bytes: Uint8Array) {}

  clone(): MockResponse {
    return new MockResponse(this.bytes);
  }

  async arrayBuffer(): Promise<ArrayBuffer> {
    return this.bytes.slice().buffer;
  }
}

class MockCache {
  readonly entries = new Map<string, MockResponse>();
  putCalls = 0;
  failNextPuts = 0;
  keysGate: Promise<void> | null = null;
  keysCalls = 0;
  onFirstKeys: (() => void) | null = null;

  async keys(): Promise<Request[]> {
    this.keysCalls += 1;
    if (this.keysCalls === 1) this.onFirstKeys?.();
    if (this.keysGate !== null) await this.keysGate;
    return [...this.entries.keys()].map((url) => ({ url }) as Request);
  }

  async match(request: Request | string): Promise<MockResponse | undefined> {
    return this.entries.get(
      typeof request === "string" ? request : request.url,
    );
  }

  async put(request: Request | string, response: MockResponse): Promise<void> {
    this.putCalls += 1;
    if (this.failNextPuts > 0) {
      this.failNextPuts -= 1;
      throw new Error("mock cache write failed");
    }
    this.entries.set(
      typeof request === "string" ? request : request.url,
      response,
    );
  }
}

type QueueImmutableCacheWrite = (
  cache: MockCache,
  url: URL,
  response: MockResponse,
) => Promise<void>;

function loadQueueImmutableCacheWrite(): QueueImmutableCacheWrite {
  const source = workerTemplate
    .replaceAll("__APP_VERSION__", "a".repeat(64))
    .replaceAll("__APP_CODE_VERSION__", "test")
    .replace("__APP_ASSETS__", "[]");
  const context: Record<string, unknown> = {
    self: { addEventListener: () => undefined },
  };
  runInNewContext(
    `${source}\nglobalThis.__queueImmutableCacheWrite = queueImmutableCacheWrite;`,
    context,
  );
  return context.__queueImmutableCacheWrite as QueueImmutableCacheWrite;
}

describe("service worker immutable cache writes", () => {
  it("coalesces concurrent writes for the same URL", async () => {
    const queueWrite = loadQueueImmutableCacheWrite();
    const cache = new MockCache();
    let releaseKeys!: () => void;
    cache.keysGate = new Promise<void>((resolve) => {
      releaseKeys = resolve;
    });
    const url = new URL(
      "https://example.test/app/_app/immutable/chunks/shared.js",
    );
    const firstResponse = new MockResponse(new Uint8Array([1, 2, 3]));
    const duplicateResponse = new MockResponse(new Uint8Array([1, 2, 3]));

    const firstWrite = queueWrite(cache, url, firstResponse);
    const duplicateWrite = queueWrite(cache, url, duplicateResponse);

    expect(duplicateWrite).toBe(firstWrite);
    expect(duplicateResponse.cancelled).toBe(true);
    releaseKeys();
    await Promise.all([firstWrite, duplicateWrite]);

    expect(cache.putCalls).toBe(1);
    expect(
      Array.from(
        new Uint8Array(await cache.entries.get(url.href)!.arrayBuffer()),
      ),
    ).toEqual([1, 2, 3]);
  });

  it("allows a failed write for a URL to be retried", async () => {
    const queueWrite = loadQueueImmutableCacheWrite();
    const cache = new MockCache();
    cache.failNextPuts = 1;
    const url = new URL(
      "https://example.test/app/_app/immutable/chunks/retry.js",
    );

    const failedWrite = queueWrite(
      cache,
      url,
      new MockResponse(new Uint8Array([4])),
    );
    const duplicateWrite = queueWrite(
      cache,
      url,
      new MockResponse(new Uint8Array([4])),
    );
    expect(duplicateWrite).toBe(failedWrite);
    await expect(failedWrite).rejects.toThrow("mock cache write failed");

    const retry = queueWrite(cache, url, new MockResponse(new Uint8Array([5])));
    expect(retry).not.toBe(failedWrite);
    await retry;

    expect(cache.putCalls).toBe(2);
    expect(cache.entries.has(url.href)).toBe(true);
    expect(
      Array.from(
        new Uint8Array(await cache.entries.get(url.href)!.arrayBuffer()),
      ),
    ).toEqual([5]);
  });

  it("keeps writes for different URLs serialized", async () => {
    const queueWrite = loadQueueImmutableCacheWrite();
    const cache = new MockCache();
    let releaseKeys!: () => void;
    cache.keysGate = new Promise<void>((resolve) => {
      releaseKeys = resolve;
    });
    const firstKeysStarted = new Promise<void>((resolve) => {
      cache.onFirstKeys = resolve;
    });

    const firstWrite = queueWrite(
      cache,
      new URL("https://example.test/app/_app/immutable/chunks/first.js"),
      new MockResponse(new Uint8Array([1])),
    );
    const secondWrite = queueWrite(
      cache,
      new URL("https://example.test/app/_app/immutable/chunks/second.js"),
      new MockResponse(new Uint8Array([2])),
    );
    await firstKeysStarted;
    expect(cache.keysCalls).toBe(1);

    releaseKeys();
    await Promise.all([firstWrite, secondWrite]);

    expect(cache.keysCalls).toBe(2);
    expect(cache.putCalls).toBe(2);
  });

  it("keeps the per-cache asset-count quota", async () => {
    const queueWrite = loadQueueImmutableCacheWrite();
    const cache = new MockCache();
    for (let index = 0; index < 512; index += 1) {
      cache.entries.set(
        `https://example.test/app/_app/immutable/chunks/${index}.js`,
        new MockResponse(new Uint8Array()),
      );
    }

    await queueWrite(
      cache,
      new URL("https://example.test/app/_app/immutable/chunks/extra.js"),
      new MockResponse(new Uint8Array([1])),
    );

    expect(cache.putCalls).toBe(0);
    expect(cache.entries.size).toBe(512);
  });

  it("keeps the aggregate byte quota", async () => {
    const queueWrite = loadQueueImmutableCacheWrite();
    const cache = new MockCache();
    cache.entries.set(
      "https://example.test/app/_app/immutable/chunks/existing.js",
      new MockResponse(new Uint8Array(25 * 1024 * 1024)),
    );

    await queueWrite(
      cache,
      new URL("https://example.test/app/_app/immutable/chunks/extra.js"),
      new MockResponse(new Uint8Array([1])),
    );

    expect(cache.putCalls).toBe(0);
    expect(cache.entries.size).toBe(1);
  });
});
