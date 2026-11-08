import { describe, expect, it } from "vitest";
import {
  notificationOptions,
  notificationState,
  safeAppPath,
  thumbnailDataUrl,
} from "./web-notifications.svelte.ts";

describe("safeAppPath", () => {
  it("keeps same-origin app paths with their query", () => {
    expect(safeAppPath("/app/projects/3")).toBe("/app/projects/3");
    expect(safeAppPath("/app/yarns?scope=all")).toBe("/app/yarns?scope=all");
  });

  it("falls back to the app start for anything else", () => {
    expect(safeAppPath("https://example.com/app/projects/3")).toBe("/app/");
    expect(safeAppPath("//example.com/app/")).toBe("/app/");
    expect(safeAppPath("/api/v2/projects")).toBe("/app/");
    expect(safeAppPath("/app/../api/v2/projects")).toBe("/app/");
    expect(safeAppPath(undefined)).toBe("/app/");
    expect(safeAppPath(42)).toBe("/app/");
  });
});

describe("notificationState", () => {
  it("is on only with permission and the switch on", () => {
    expect(notificationState(true, "granted", true)).toBe("on");
    expect(notificationState(true, "granted", false)).toBe("off");
    expect(notificationState(true, "default", true)).toBe("off");
  });

  it("reports blocked and unsupported browsers", () => {
    expect(notificationState(true, "denied", true)).toBe("denied");
    expect(notificationState(false, null, true)).toBe("unsupported");
    expect(notificationState(true, null, false)).toBe("unsupported");
  });
});

function imageResponse(body: number[], type = "image/jpeg", ok = true) {
  return Promise.resolve({
    ok,
    headers: new Headers({ "content-type": type }),
    arrayBuffer: () => Promise.resolve(new Uint8Array(body).buffer),
  } as Response);
}

describe("thumbnailDataUrl", () => {
  it("returns a data URL for a thumbnail fetched with the session", async () => {
    let init: RequestInit | undefined;
    const url = await thumbnailDataUrl(
      "/media/thumbnails/projects/3/thumb_a.jpg",
      ((_input: unknown, options?: RequestInit) => {
        init = options;
        return imageResponse([1, 2, 3]);
      }) as typeof fetch,
    );
    expect(url).toBe("data:image/jpeg;base64,AQID");
    expect(init?.credentials).toBe("same-origin");
  });

  it("refuses other paths and failed or non-image responses", async () => {
    const never = (() => {
      throw new Error("must not fetch");
    }) as typeof fetch;
    expect(await thumbnailDataUrl(null, never)).toBeNull();
    expect(await thumbnailDataUrl("/api/v2/me", never)).toBeNull();
    expect(
      await thumbnailDataUrl("https://example.com/media/thumbnails/a", never),
    ).toBeNull();
    const path = "/media/thumbnails/projects/3/thumb_a.jpg";
    expect(
      await thumbnailDataUrl(path, (() =>
        imageResponse([1], "image/jpeg", false)) as typeof fetch),
    ).toBeNull();
    expect(
      await thumbnailDataUrl(path, (() =>
        imageResponse([1], "text/html")) as typeof fetch),
    ).toBeNull();
    expect(
      await thumbnailDataUrl(path, (() =>
        Promise.reject(new Error("offline"))) as typeof fetch),
    ).toBeNull();
  });
});

describe("notificationOptions", () => {
  const base = {
    title: "Blanket: timer still running",
    body: "1 h 00 min spent so far.",
    tag: "project-timer-3",
    path: "/app/projects/3",
  };

  it("stays plain without a picture", () => {
    const options = notificationOptions(base, null);
    expect(options.icon).toBe("/app/icons/icon-192.png");
    expect(options.image).toBeUndefined();
    expect(options.actions).toBeUndefined();
    expect(options.data).toEqual({ path: "/app/projects/3" });
  });

  it("adds the picture, timestamp and open action", () => {
    const options = notificationOptions(
      { ...base, openLabel: "Open project", timestamp: 1234, path: "/evil" },
      "data:image/jpeg;base64,AQID",
    );
    expect(options.icon).toBe("data:image/jpeg;base64,AQID");
    expect(options.image).toBe("data:image/jpeg;base64,AQID");
    expect(options.timestamp).toBe(1234);
    expect(options.data).toEqual({ path: "/app/" });
    expect(options).toMatchObject({
      renotify: true,
      actions: [{ action: "open", title: "Open project" }],
    });
  });
});
