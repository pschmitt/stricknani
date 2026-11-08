import { describe, expect, it } from "vitest";
import {
  parseRestoreResult,
  restoreContentType,
  restoreErrorKind,
  uploadPercent,
  encodePassphrase,
  encryptedExportBody,
  parseExportTicket,
  passphraseBytes,
  passphraseProblem,
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

describe("encryption", () => {
  it("encodes the UTF-8 bytes as base64url without padding", () => {
    expect(encodePassphrase("abc")).toBe("YWJj");
    expect(encodePassphrase("a")).toBe("YQ");
    // 0xfb 0xff would be "+/" in plain base64.
    expect(encodePassphrase("\u00fb")).toBe("w7s");
    expect(encodePassphrase("Gr\u00fc\u00dfe \u{1F9F6}")).toBe(
      "R3LDvMOfZSDwn6e2",
    );
  });

  it("counts UTF-8 bytes, not characters", () => {
    expect(passphraseBytes("\u00e4\u00f6\u00fc")).toBe(6);
    expect(passphraseProblem("\u00e4\u00f6\u00fc\u00e4\u00f6\u00fc", "x")).toBe(
      "mismatch",
    );
    expect(passphraseProblem("eleven char", "eleven char")).toBe("too_short");
    expect(passphraseProblem("twelve chars", "twelve chars")).toBeUndefined();
    expect(passphraseProblem("twelve chars", "twelve charz")).toBe("mismatch");
  });

  it("builds the request body and reads the ticket", () => {
    expect(encryptedExportBody("zip", "pw")).toEqual({
      archive: "zip",
      passphrase: "pw",
    });
    expect(
      parseExportTicket({ data: { url: "/api/v2/account/export/download/x" } }),
    ).toBe("/api/v2/account/export/download/x");
    expect(parseExportTicket({ data: { url: "https://evil.example/" } })).toBe(
      undefined,
    );
    expect(parseExportTicket({ data: { url: "//evil.example/" } })).toBe(
      undefined,
    );
    expect(parseExportTicket(null)).toBe(undefined);
  });

  it("maps the passphrase errors and age file types", () => {
    expect(
      restoreErrorKind(422, { error: { code: "passphrase_required" } }),
    ).toBe("passphrase_required");
    expect(restoreErrorKind(422, { error: { code: "wrong_passphrase" } })).toBe(
      "wrong_passphrase",
    );
    expect(restoreContentType({ name: "a.tar.gz.age" })).toBe(
      "application/gzip",
    );
    expect(restoreContentType({ name: "a.zip.age" })).toBe("application/zip");
  });
});
