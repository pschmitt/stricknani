import { describe, expect, it } from "vitest";
import {
  compareBackups,
  countsDiffer,
  formatBytes,
  formatCount,
} from "./backups-format";

describe("backup formatting", () => {
  it("formats sizes", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1023)).toBe("1023 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
    expect(formatBytes(250 * 1024 * 1024)).toBe("250 MB");
    expect(formatBytes(3 * 1024 ** 3)).toBe("3.0 GB");
    expect(formatBytes(-1)).toBe("");
  });

  it("shows unknown counts as a dash", () => {
    expect(formatCount(null)).toBe("–");
    expect(formatCount(0)).toBe("0");
    expect(formatCount(12)).toBe("12");
  });

  it("sorts newest first", () => {
    const rows = [
      { created_at: "2026-01-01T00:00:00Z", name: "b" },
      { created_at: "2026-01-02T00:00:00Z", name: "a" },
      { created_at: "2026-01-01T00:00:00Z", name: "a" },
    ];
    expect(rows.sort(compareBackups).map((row) => row.name)).toEqual([
      "a",
      "a",
      "b",
    ]);
    expect(rows[0]?.created_at).toBe("2026-01-02T00:00:00Z");
  });

  it("spots differing counts", () => {
    const a = { users: 1, projects: 2, yarns: null, trashed: 0 };
    const b = { users: 1, projects: 3, yarns: null, trashed: 0 };
    expect(countsDiffer(a, b, "users")).toBe(false);
    expect(countsDiffer(a, b, "projects")).toBe(true);
    expect(countsDiffer(a, null, "yarns")).toBe(false);
    expect(countsDiffer(null, b, "projects")).toBe(true);
  });
});
