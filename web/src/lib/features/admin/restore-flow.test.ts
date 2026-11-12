import { describe, expect, it } from "vitest";
import type { BackupEntry, RestorePreview } from "./backups-api";
import {
  CLOSED,
  REAUTH_WINDOW_MS,
  canRestore,
  confirmationMatches,
  isLocked,
  reauthIsFresh,
  restoreReducer,
  type RestoreEvent,
  type RestoreState,
} from "./restore-flow";

const backup: BackupEntry = {
  name: "stricknani-20260101-020000.tar.gz",
  kind: "nightly",
  created_at: "2026-01-01T02:00:00Z",
  size_bytes: 10,
  has_media: true,
  schema_version: 37,
  counts: { users: 1, projects: 1, yarns: 1, trashed: 0 },
  readable: true,
  newer_schema: false,
  restorable: true,
  error: null,
};

const preview: RestorePreview = {
  backup,
  current: { schema_version: 37, counts: backup.counts },
  backup_snapshot: { schema_version: 37, counts: backup.counts },
  changes_since_backup: 4,
  media_options: ["none", "add_missing"],
  restarts_server: true,
};

const accepted = {
  backup: backup.name,
  pre_restore: "pre-restore-20260102-030000.sqlite3",
  restarting: true,
};

function run(
  events: RestoreEvent[],
  from: RestoreState = CLOSED,
): RestoreState {
  return events.reduce(restoreReducer, from);
}

const NOW = 1_000_000;

describe("restore flow", () => {
  it("walks preview, re-authentication, confirmation and restore", () => {
    let state = run([{ type: "open", backup }]);
    expect(state.phase).toBe("loading");
    state = run([{ type: "preview_loaded", preview }], state);
    expect(state.phase).toBe("preview");
    state = run([{ type: "continue", now: NOW }], state);
    expect(state.phase).toBe("reauth");
    state = run([{ type: "reauth_confirmed", now: NOW }], state);
    expect(state.phase).toBe("confirm");
    expect(canRestore(state)).toBe(false);
    state = run([{ type: "typed", value: backup.name }], state);
    expect(canRestore(state)).toBe(true);
    state = run([{ type: "submit" }], state);
    expect(state.phase).toBe("restoring");
    expect(isLocked(state)).toBe(true);
    state = run([{ type: "accepted", accepted, now: NOW }], state);
    expect(state.phase).toBe("waiting");
    expect(isLocked(state)).toBe(true);
    state = run([{ type: "server_back", result: "succeeded" }], state);
    expect(state.phase).toBe("succeeded");
    expect(run([{ type: "close" }], state)).toEqual(CLOSED);
  });

  it("only restorable backups open", () => {
    expect(
      run([{ type: "open", backup: { ...backup, restorable: false } }]),
    ).toEqual(CLOSED);
  });

  it("needs the exact name, never a near miss", () => {
    expect(confirmationMatches(backup.name, backup.name)).toBe(true);
    expect(confirmationMatches(backup.name.toUpperCase(), backup.name)).toBe(
      false,
    );
    expect(confirmationMatches(` ${backup.name}`, backup.name)).toBe(false);
    let state = run([
      { type: "open", backup },
      { type: "preview_loaded", preview },
      { type: "continue", now: NOW },
      { type: "reauth_confirmed", now: NOW },
      { type: "typed", value: "stricknani" },
      { type: "submit" },
    ]);
    expect(state.phase).toBe("confirm");
    state = run([{ type: "typed", value: backup.name }], state);
    expect(canRestore(state)).toBe(true);
  });

  it("skips the prompt while a recent confirmation is still valid", () => {
    const state = run([
      { type: "open", backup, reauthedAt: NOW - 60_000 },
      { type: "preview_loaded", preview },
      { type: "continue", now: NOW },
    ]);
    expect(state.phase).toBe("confirm");
    const stale = run([
      { type: "open", backup, reauthedAt: NOW - REAUTH_WINDOW_MS },
      { type: "preview_loaded", preview },
      { type: "continue", now: NOW },
    ]);
    expect(stale.phase).toBe("reauth");
    expect(reauthIsFresh(null, NOW)).toBe(false);
    expect(reauthIsFresh(NOW + 5, NOW)).toBe(false);
  });

  it("returns to the preview when the prompt is cancelled and keeps the media choice", () => {
    let state = run([
      { type: "open", backup },
      { type: "preview_loaded", preview },
      { type: "set_media", media: "add_missing" },
      { type: "continue", now: NOW },
      { type: "reauth_cancelled" },
    ]);
    expect(state).toMatchObject({ phase: "preview", media: "add_missing" });
    state = run([{ type: "set_media", media: "none" }], state);
    expect(state).toMatchObject({ media: "none" });
  });

  it("rejects a media option the preview does not offer", () => {
    const state = run([
      { type: "open", backup },
      {
        type: "preview_loaded",
        preview: { ...preview, media_options: ["none"] },
      },
      { type: "set_media", media: "add_missing" },
    ]);
    expect(state).toMatchObject({ media: "none" });
  });

  it("asks again when the server says the confirmation expired", () => {
    const state = run([
      { type: "open", backup },
      { type: "preview_loaded", preview },
      { type: "continue", now: NOW },
      { type: "reauth_confirmed", now: NOW },
      { type: "typed", value: backup.name },
      { type: "submit" },
      { type: "rejected", error: "unknown", reauthRequired: true },
    ]);
    expect(state).toMatchObject({ phase: "reauth", reauthedAt: null });
  });

  it("keeps what was typed when the server rejects the request", () => {
    const state = run([
      { type: "open", backup },
      { type: "preview_loaded", preview },
      { type: "continue", now: NOW },
      { type: "reauth_confirmed", now: NOW },
      { type: "typed", value: backup.name },
      { type: "submit" },
      { type: "rejected", error: "newer_schema" },
    ]);
    expect(state).toMatchObject({
      phase: "confirm",
      typed: backup.name,
      error: "newer_schema",
    });
  });

  it("cannot be dismissed while restoring or waiting, and waits for a manual restart", () => {
    const restoring = run([
      { type: "open", backup },
      { type: "preview_loaded", preview },
      { type: "continue", now: NOW },
      { type: "reauth_confirmed", now: NOW },
      { type: "typed", value: backup.name },
      { type: "submit" },
    ]);
    expect(run([{ type: "close" }], restoring)).toBe(restoring);
    const manual = run(
      [
        {
          type: "accepted",
          accepted: { ...accepted, restarting: false },
          now: NOW,
        },
      ],
      restoring,
    );
    expect(manual.phase).toBe("manual_restart");
    expect(run([{ type: "close" }], manual)).toEqual(CLOSED);
  });

  it("ends in failure or timeout from the restart poll", () => {
    const waiting = run([
      { type: "open", backup },
      { type: "preview_loaded", preview },
      { type: "continue", now: NOW },
      { type: "reauth_confirmed", now: NOW },
      { type: "typed", value: backup.name },
      { type: "submit" },
      { type: "accepted", accepted, now: NOW },
    ]);
    expect(
      run([{ type: "server_back", result: "failed", error: "boom" }], waiting),
    ).toMatchObject({ phase: "failed", error: "boom" });
    expect(
      run([{ type: "server_back", result: "timeout" }], waiting).phase,
    ).toBe("timeout");
  });

  it("ignores events that do not fit the phase", () => {
    expect(run([{ type: "submit" }])).toEqual(CLOSED);
    expect(run([{ type: "server_back", result: "succeeded" }])).toEqual(CLOSED);
    expect(run([{ type: "typed", value: "x" }])).toEqual(CLOSED);
  });
});
