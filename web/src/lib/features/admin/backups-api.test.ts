import { afterEach, describe, expect, it, vi } from "vitest";
import {
  listBackups,
  parseBackupEntry,
  previewRestore,
  restoreBackup,
  waitForRestart,
} from "./backups-api";

const session = {
  user: { id: 1, email: "a@example.invalid", is_admin: true, avatar_url: null },
  csrf_token: "csrf-test",
  capabilities: {
    signup: false,
    external_imports: false,
    ai: false,
    ocr: false,
    archive: false,
    media_upload: false,
    media_delete: false,
    import_preview: false,
    archive_state: false,
    account_actions: true,
    admin_actions: true,
    passkeys: false,
  },
  locale: "en" as const,
};

const entry = {
  name: "stricknani-20260101-020000.tar.gz",
  kind: "nightly",
  created_at: "2026-01-01T02:00:00Z",
  size_bytes: 1234,
  has_media: true,
  schema_version: 37,
  counts: { users: 3, projects: 2, yarns: 1, trashed: 1 },
  readable: true,
  newer_schema: false,
  restorable: true,
  error: null,
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("backups API", () => {
  it("parses the listing and tolerates unknown kinds", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: {
            available: true,
            server_schema_version: 37,
            backups: [entry, { ...entry, name: "x.sqlite3", kind: "weird" }],
            latest_pre_restore: "pre-restore-20260102-030000.sqlite3",
            pending_restore: null,
            last_restore: {
              status: "failed",
              backup: "x.sqlite3",
              pre_restore: "pre-restore-1.sqlite3",
              media: "none",
              finished_at: "2026-01-03T00:00:00Z",
              requested_by_email: "a@example.invalid",
              error: "boom",
              media_added: null,
              media_error: null,
            },
            data_epoch: "abc",
            auto_restart: true,
          },
        }),
      ),
    );
    const list = await listBackups();
    expect(list.backups).toHaveLength(2);
    expect(list.backups[0]?.counts?.users).toBe(3);
    expect(list.backups[1]?.kind).toBe("manual");
    expect(list.latest_pre_restore).toBe("pre-restore-20260102-030000.sqlite3");
    expect(list.last_restore).toMatchObject({
      status: "failed",
      error: "boom",
    });
    expect(list.auto_restart).toBe(true);
  });

  it("rejects a malformed entry", () => {
    expect(() => parseBackupEntry({ kind: "nightly" })).toThrow();
  });

  it("encodes the name in preview and restore requests", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({
          data: {
            backup: entry,
            current: { schema_version: 37, counts: entry.counts },
            backup_snapshot: { schema_version: 37, counts: entry.counts },
            changes_since_backup: 12,
            media_options: ["none", "add_missing", "bogus"],
            restarts_server: true,
          },
        }),
      )
      .mockResolvedValueOnce(
        Response.json(
          {
            data: {
              backup: entry.name,
              pre_restore: "pre-restore-1.sqlite3",
              restarting: true,
            },
          },
          { status: 202 },
        ),
      );
    vi.stubGlobal("fetch", fetchMock);
    const preview = await previewRestore("a b.sqlite3");
    expect(preview.media_options).toEqual(["none", "add_missing"]);
    expect(preview.changes_since_backup).toBe(12);
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      "/api/v2/admin/backups/a%20b.sqlite3/restore-preview",
    );
    const accepted = await restoreBackup(
      entry.name,
      entry.name,
      "add_missing",
      session,
    );
    expect(accepted.pre_restore).toBe("pre-restore-1.sqlite3");
    const [url, init] = fetchMock.mock.calls[1] ?? [];
    expect(url).toBe(`/api/v2/admin/backups/${entry.name}/restore`);
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({
      confirmation: entry.name,
      media: "add_missing",
    });
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("csrf-test");
  });
});

describe("waiting for the restart", () => {
  /** A scripted server: each step answers `/healthz` and `/api/v2/meta`. */
  function scripted(
    steps: Array<
      | "down"
      | { epoch: string | null; last?: { status: string; finished_at: string } }
    >,
  ) {
    let index = 0;
    return vi.fn(async (input: RequestInfo | URL) => {
      const step = steps[Math.min(index, steps.length - 1)];
      const url = String(input);
      if (url === "/healthz") {
        if (step === "down") {
          index += 1;
          throw new TypeError("offline");
        }
        return Response.json({ status: "ok" });
      }
      const meta = step as {
        epoch: string | null;
        last?: { status: string; finished_at: string };
      };
      index += 1;
      return Response.json({
        data: { data_epoch: meta.epoch, last_restore: meta.last ?? null },
      });
    }) as unknown as typeof fetch;
  }

  const base = {
    previousEpoch: "old",
    requestedAtMs: Date.parse("2026-01-03T00:00:00Z"),
    intervalMs: 1,
    sleep: async () => undefined,
  };

  it("reports success when the epoch changed after the server came back", async () => {
    const result = await waitForRestart({
      ...base,
      fetchImpl: scripted(["down", "down", { epoch: "new" }]),
    });
    expect(result).toEqual({ kind: "succeeded" });
  });

  it("reports a failed restore from the recorded outcome", async () => {
    const result = await waitForRestart({
      ...base,
      fetchImpl: scripted([
        "down",
        {
          epoch: "old",
          last: { status: "failed", finished_at: "2026-01-03T00:00:05Z" },
        },
      ]),
    });
    expect(result.kind).toBe("failed");
  });

  it("does not trust the old process before it went down", async () => {
    let ticks = 0;
    const result = await waitForRestart({
      ...base,
      fetchImpl: scripted([{ epoch: "new" }]),
      timeoutMs: 5,
      now: () => ticks++,
    });
    expect(result).toEqual({ kind: "timeout" });
  });

  it("treats a restart that recorded nothing as a failure", async () => {
    const result = await waitForRestart({
      ...base,
      fetchImpl: scripted(["down", { epoch: "old" }]),
    });
    expect(result.kind).toBe("failed");
  });

  it("ignores an outcome from an earlier restore", async () => {
    const result = await waitForRestart({
      ...base,
      fetchImpl: scripted([
        "down",
        {
          epoch: "old",
          last: { status: "succeeded", finished_at: "2020-01-01T00:00:00Z" },
        },
      ]),
    });
    expect(result.kind).toBe("failed");
  });
});
