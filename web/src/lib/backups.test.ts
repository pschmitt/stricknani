import { describe, expect, it } from "vitest";
import {
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
