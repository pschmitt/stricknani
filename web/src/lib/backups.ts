/** Same-origin download of the per-user data export (see docs/data-export.md). */
export const exportPath = "/api/v2/account/export";

/** Version of the archive format the server writes. */
export const exportFormatVersion = 1;

export type ArchiveType = "tar.gz" | "zip";

export const archiveTypes: readonly ArchiveType[] = ["tar.gz", "zip"];

const storageKey = "stricknani.backups.archive";

/** The export URL for an archive type; .tar.gz is the server default. */
export function exportUrl(type: ArchiveType): string {
  return type === "zip" ? `${exportPath}?archive=zip` : exportPath;
}

export function parseArchiveType(value: unknown): ArchiveType {
  return value === "zip" ? "zip" : "tar.gz";
}

export function readArchiveType(): ArchiveType {
  try {
    return parseArchiveType(localStorage.getItem(storageKey));
  } catch {
    return "tar.gz";
  }
}

export function writeArchiveType(type: ArchiveType): void {
  try {
    localStorage.setItem(storageKey, type);
  } catch {
    // Remembering the choice is optional.
  }
}
