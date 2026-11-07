import { describe, expect, it } from "vitest";
import {
  parseRestoreResult,
  restoreContentType,
  restoreErrorKind,
  uploadPercent,
  exportFormatVersion,
  exportUrl,
  parseArchiveType,
  readArchiveType,
  writeArchiveType,
} from "./backups";

describe("backups", () => {
  it("downloads .tar.gz from the plain export endpoint", () => {
    expect(exportUrl("tar.gz")).toBe("/api/v2/account/export");
  });

  it("selects zip through the archive parameter", () => {
    expect(exportUrl("zip")).toBe("/api/v2/account/export?archive=zip");
  });

  it("falls back to .tar.gz for unknown values", () => {
    expect(parseArchiveType("zip")).toBe("zip");
    expect(parseArchiveType("rar")).toBe("tar.gz");
    expect(parseArchiveType(null)).toBe("tar.gz");
  });

  it("survives missing storage", () => {
    expect(readArchiveType()).toBe("tar.gz");
    expect(() => writeArchiveType("zip")).not.toThrow();
  });

  it("knows the export format version", () => {
    expect(exportFormatVersion).toBe(1);
  });
});

describe("restore", () => {
  it("picks the content type from the file", () => {
    expect(restoreContentType({ name: "a.tar.gz" })).toBe("application/gzip");
    expect(restoreContentType({ name: "A.TGZ" })).toBe("application/gzip");
    expect(restoreContentType({ name: "a.ZIP" })).toBe("application/zip");
    expect(restoreContentType({ name: "x", type: "application/zip" })).toBe(
      "application/zip",
    );
  });

  it("reads the success body", () => {
    expect(
      parseRestoreResult({
        data: {
          format_version: 1,
          projects: 12,
          yarns: 30,
          categories_created: 2,
          media_files: 84,
          media_skipped: 1,
        },
      }),
    ).toEqual({ projects: 12, yarns: 30, mediaFiles: 84, mediaSkipped: 1 });
    expect(
      parseRestoreResult({ data: { projects: 0, yarns: 0, media_files: 0 } }),
    ).toEqual({ projects: 0, yarns: 0, mediaFiles: 0, mediaSkipped: 0 });
  });

  it("rejects malformed success bodies", () => {
    expect(parseRestoreResult(null)).toBeUndefined();
    expect(parseRestoreResult({})).toBeUndefined();
    expect(parseRestoreResult({ data: { projects: "1" } })).toBeUndefined();
    expect(
      parseRestoreResult({ data: { projects: -1, yarns: 0, media_files: 0 } }),
    ).toBeUndefined();
  });

  it("maps error codes and statuses", () => {
    const err = (code: string) => ({ error: { code, message: "x" } });
    expect(restoreErrorKind(400, err("invalid_archive"))).toBe(
      "invalid_archive",
    );
    expect(restoreErrorKind(422, err("unsupported_format_version"))).toBe(
      "unsupported_format_version",
    );
    expect(restoreErrorKind(413, err("payload_too_large"))).toBe(
      "payload_too_large",
    );
    expect(restoreErrorKind(413, undefined)).toBe("payload_too_large");
    expect(restoreErrorKind(429, err("rate_limited"))).toBe("rate_limited");
    expect(restoreErrorKind(429, undefined)).toBe("rate_limited");
    expect(restoreErrorKind(500, err("internal"))).toBe("failed");
    expect(restoreErrorKind(502, "<html>")).toBe("failed");
  });

  it("computes upload percent", () => {
    expect(uploadPercent(0, 100)).toBe(0);
    expect(uploadPercent(50, 200)).toBe(25);
    expect(uploadPercent(300, 200)).toBe(100);
    expect(uploadPercent(5, 0)).toBe(0);
  });
});
