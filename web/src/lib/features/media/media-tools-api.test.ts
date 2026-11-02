import { afterEach, describe, expect, it, vi } from "vitest";
import type { SessionData } from "#lib/api.ts";
import {
  ARCHIVE_POLL_DEADLINE_MS,
  ARCHIVE_POLL_DELAYS_MS,
  canManuallyCheckArchive,
  getArchiveStatus,
  parseArchiveStatus,
  pollArchiveStatus,
  recognizeImageText,
  requestArchive,
  type ArchiveStatus,
} from "./media-tools-api";

const session: SessionData = {
  user: {
    id: 42,
    email: "demo@example.invalid",
    is_admin: false,
    avatar_url: null,
  },
  csrf_token: "fixture-csrf",
  capabilities: {
    signup: false,
    external_imports: false,
    ai: false,
    ocr: true,
    archive: true,
    media_upload: false,
    media_delete: false,
    import_preview: false,
    archive_state: true,
    account_actions: true,
    admin_actions: false,
  },
  locale: "en",
};

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("media tool API", () => {
  it("bounds archive polling to ten increasing intervals under two minutes", () => {
    expect(ARCHIVE_POLL_DELAYS_MS).toHaveLength(10);
    expect(ARCHIVE_POLL_DELAYS_MS.slice(0, 3)).toEqual([2_000, 4_000, 8_000]);
    expect(
      ARCHIVE_POLL_DELAYS_MS.slice(3).every((delay) => delay === 15_000),
    ).toBe(true);
    expect(ARCHIVE_POLL_DELAYS_MS.reduce((sum, delay) => sum + delay, 0)).toBe(
      119_000,
    );
    expect(ARCHIVE_POLL_DEADLINE_MS).toBe(120_000);
  });

  it("aborts a slow archive status request at the deadline and enables a manual check", async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    let currentStatus: ArchiveStatus = {
      state: "running",
      updated_at: 1793558400,
    };
    let pollingExhausted = false;
    const readStatus = vi.fn(
      (signal: AbortSignal) =>
        new Promise<ArchiveStatus>((_resolve, reject) => {
          signal.addEventListener(
            "abort",
            () => reject(new DOMException("Request aborted", "AbortError")),
            { once: true },
          );
        }),
    );
    const polling = pollArchiveStatus({
      controller,
      readStatus,
      onStatus: (nextStatus) => {
        currentStatus = nextStatus;
      },
      onDeadline: () => {
        pollingExhausted = true;
      },
      wait: async () => {},
    });

    await Promise.resolve();
    await Promise.resolve();
    expect(readStatus).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(ARCHIVE_POLL_DEADLINE_MS);
    await polling;

    expect(controller.signal.aborted).toBe(true);
    expect(readStatus).toHaveBeenCalledOnce();
    expect(currentStatus).toEqual({
      state: "running",
      updated_at: 1793558400,
    });
    expect(pollingExhausted).toBe(true);
    expect(canManuallyCheckArchive(currentStatus, pollingExhausted)).toBe(true);
  });

  it("sends an explicit, same-origin OCR request and returns only its text", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        Response.json({ data: { text: "Sleeve: knit 12 rows" } }),
      );
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();

    await expect(
      recognizeImageText(
        "/media/projects/42/step.png",
        "deu+eng",
        session,
        controller.signal,
      ),
    ).resolves.toEqual({ text: "Sleeve: knit 12 rows" });

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v2/utils/ocr");
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      signal: controller.signal,
      body: JSON.stringify({
        src: "/media/projects/42/step.png",
        lang: "deu+eng",
        force: false,
      }),
      headers: expect.any(Headers),
    });
    const requestHeaders = new Headers(
      (fetchMock.mock.calls[0]?.[1] as RequestInit).headers,
    );
    expect(requestHeaders.get("x-csrf-token")).toBe("fixture-csrf");
    expect(requestHeaders.get("origin")).toBeNull();
  });

  it("enqueues archives with CSRF and validates pending, null, and terminal states", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({ data: { state: "pending" } }, { status: 202 }),
      )
      .mockResolvedValueOnce(
        Response.json({ data: { state: "running", updated_at: 1793558400 } }),
      )
      .mockResolvedValueOnce(
        Response.json({ data: { state: null, updated_at: null } }),
      );
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();

    await expect(
      requestArchive("projects", 42, session, controller.signal),
    ).resolves.toEqual({ state: "pending", updated_at: null });
    await expect(
      getArchiveStatus("yarns", 7, controller.signal),
    ).resolves.toEqual({ state: "running", updated_at: 1793558400 });
    await expect(
      getArchiveStatus("projects", 42, controller.signal),
    ).resolves.toEqual({ state: null, updated_at: null });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      "/api/v2/projects/42/archive",
      "/api/v2/yarns/7/archive",
      "/api/v2/projects/42/archive",
    ]);
    const enqueueInit = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(enqueueInit).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      signal: controller.signal,
      body: "{}",
    });
    expect(new Headers(enqueueInit.headers).get("x-csrf-token")).toBe(
      "fixture-csrf",
    );
    expect(new Headers(enqueueInit.headers).get("origin")).toBeNull();
  });

  it("rejects invalid OCR results and malformed or inconsistent archive status", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ data: { text: 7 } }))
        .mockResolvedValueOnce(
          Response.json({ data: { state: "mystery", updated_at: null } }),
        ),
    );
    const controller = new AbortController();

    await expect(
      recognizeImageText(
        "/media/projects/42/image.png",
        "eng",
        session,
        controller.signal,
      ),
    ).rejects.toThrow("Text recognition returned an unreadable response.");
    await expect(
      getArchiveStatus("projects", 42, controller.signal),
    ).rejects.toThrow("Archive status returned an unreadable response.");
    expect(() =>
      parseArchiveStatus({ data: { state: null, updated_at: 10 } }),
    ).toThrow("Archive status returned an unreadable response.");
  });
});
