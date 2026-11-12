import { describe, expect, it } from "vitest";
import type { BackupEntry, BackupKind, RestoreOutcome } from "./backups-api";
import {
  groupByDay,
  kindStyle,
  relativeAge,
  undoCandidate,
} from "./backups-present";
import { KIND_ORDER } from "./backups-format";

function entry(
  name: string,
  kind: BackupKind,
  created_at: string,
): BackupEntry {
  return {
    name,
    kind,
    created_at,
    size_bytes: 1,
    has_media: false,
    schema_version: 1,
    counts: null,
    readable: true,
    newer_schema: false,
    restorable: true,
    error: null,
  };
}

function outcome(overrides: Partial<RestoreOutcome> = {}): RestoreOutcome {
  return {
    status: "succeeded",
    backup: "nightly-1.sqlite3",
    pre_restore: "pre-restore-1.sqlite3",
    media: "none",
    finished_at: "2026-11-12T10:00:00Z",
    requested_by_email: "a@example.invalid",
    error: null,
    media_added: null,
    media_error: null,
    ...overrides,
  };
}

describe("kind badges", () => {
  it("gives every kind its own tone and icon", () => {
    const styles = KIND_ORDER.map(kindStyle);
    expect(new Set(styles.map((style) => style.tone)).size).toBe(5);
    expect(new Set(styles.map((style) => style.icon)).size).toBe(5);
  });

  it("maps the kinds to the shared tones and icons", () => {
    expect(kindStyle("nightly")).toEqual({ tone: "nightly", icon: "moon" });
    expect(kindStyle("predeploy")).toEqual({ tone: "update", icon: "upload" });
    expect(kindStyle("pre_migrate")).toEqual({
      tone: "migration",
      icon: "database",
    });
    expect(kindStyle("pre_restore")).toEqual({
      tone: "restore",
      icon: "reset",
    });
    expect(kindStyle("manual")).toEqual({ tone: "manual", icon: "needle" });
  });
});

describe("undoCandidate", () => {
  const backups = [
    entry("pre-restore-1.sqlite3", "pre_restore", "2026-11-12T09:59:00Z"),
    entry("manual-x.sqlite3", "manual", "2026-11-11T09:59:00Z"),
  ];

  it("offers the safety copy of the last successful restore", () => {
    const found = undoCandidate(
      { last_restore: outcome(), pending_restore: null },
      backups,
    );
    expect(found?.name).toBe("pre-restore-1.sqlite3");
  });

  it("stays hidden without a restore record, however many pre-restore files exist", () => {
    expect(
      undoCandidate({ last_restore: null, pending_restore: null }, backups),
    ).toBeNull();
  });

  it("stays hidden after a failed restore or while one is pending", () => {
    expect(
      undoCandidate(
        { last_restore: outcome({ status: "failed" }), pending_restore: null },
        backups,
      ),
    ).toBeNull();
    expect(
      undoCandidate(
        {
          last_restore: outcome(),
          pending_restore: {
            backup: "x",
            pre_restore: "y",
            requested_at: "2026-11-12T11:00:00Z",
          },
        },
        backups,
      ),
    ).toBeNull();
  });

  it("stays hidden when the matching file is gone or not a pre-restore file", () => {
    expect(
      undoCandidate(
        {
          last_restore: outcome({ pre_restore: "pre-restore-2.sqlite3" }),
          pending_restore: null,
        },
        backups,
      ),
    ).toBeNull();
    expect(
      undoCandidate(
        {
          last_restore: outcome({ pre_restore: "manual-x.sqlite3" }),
          pending_restore: null,
        },
        backups,
      ),
    ).toBeNull();
  });
});

describe("groupByDay", () => {
  const now = new Date("2026-11-12T12:00:00Z");
  const rows = [
    entry("a", "nightly", "2026-11-12T10:00:00Z"),
    entry("b", "manual", "2026-11-12T01:00:00Z"),
    entry("c", "predeploy", "2026-11-11T20:00:00Z"),
    entry("d", "predeploy", "2026-11-01T20:00:00Z"),
  ];

  it("labels today, yesterday and older days", () => {
    const groups = groupByDay(rows, now, "UTC");
    expect(groups.map((group) => group.label.kind)).toEqual([
      "today",
      "yesterday",
      "date",
    ]);
    expect(groups[0]?.entries.map((row) => row.name)).toEqual(["a", "b"]);
  });

  it("splits days in the given time zone", () => {
    // 12:00 UTC is already the 13th in Auckland, so 11 Nov 20:00 UTC lands on
    // the same local day as the two entries of the 12th.
    const groups = groupByDay(rows, now, "Pacific/Auckland");
    expect(groups[0]?.label.kind).toBe("yesterday");
    expect(groups[0]?.entries.map((row) => row.name)).toEqual(["a", "b", "c"]);
    const tokyo = groupByDay([rows[1]!], now, "Asia/Tokyo");
    expect(tokyo[0]?.label.kind).toBe("today");
  });
});

describe("relativeAge", () => {
  const now = new Date("2026-11-12T12:00:00Z");
  it("reads minutes, hours and days", () => {
    expect(relativeAge("2026-11-12T11:48:00Z", now, "en")).toBe(
      "12 minutes ago",
    );
    expect(relativeAge("2026-11-12T09:00:00Z", now, "en")).toBe("3 hours ago");
    expect(relativeAge("2026-11-10T12:00:00Z", now, "en")).toBe("2 days ago");
    expect(relativeAge("2026-09-01T12:00:00Z", now, "en")).toBeNull();
    expect(relativeAge("bad", now, "en")).toBeNull();
  });
});
