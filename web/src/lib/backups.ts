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

/** Same-origin restore endpoint (see docs/data-export.md, "Restore"). */
export const importPath = "/api/v2/account/import";

/** File types the restore picker offers. */
export const restoreAccept =
  ".tar.gz,.tgz,.zip,application/gzip,application/zip";

export interface RestoreResult {
  projects: number;
  yarns: number;
  mediaFiles: number;
  mediaSkipped: number;
}

export type RestoreErrorKind =
  | "invalid_archive"
  | "unsupported_format_version"
  | "payload_too_large"
  | "rate_limited"
  | "failed";

/** Content-Type of the raw request body, from the file name or its type. */
export function restoreContentType(file: {
  name: string;
  type?: string;
}): "application/gzip" | "application/zip" {
  const name = file.name.toLowerCase();
  if (name.endsWith(".zip") || file.type === "application/zip")
    return "application/zip";
  return "application/gzip";
}

function count(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : undefined;
}

/** Reads the success body; undefined when it has an unexpected shape. */
export function parseRestoreResult(body: unknown): RestoreResult | undefined {
  if (typeof body !== "object" || body === null) return;
  const data = (body as Record<string, unknown>).data;
  if (typeof data !== "object" || data === null) return;
  const record = data as Record<string, unknown>;
  const projects = count(record.projects);
  const yarns = count(record.yarns);
  const mediaFiles = count(record.media_files);
  if (projects === undefined || yarns === undefined || mediaFiles === undefined)
    return;
  return {
    projects,
    yarns,
    mediaFiles,
    mediaSkipped: count(record.media_skipped) ?? 0,
  };
}

/** Maps an error response (HTTP status and v2 envelope) to a message kind. */
export function restoreErrorKind(
  status: number,
  body: unknown,
): RestoreErrorKind {
  let code: unknown;
  if (typeof body === "object" && body !== null) {
    const error = (body as Record<string, unknown>).error;
    if (typeof error === "object" && error !== null)
      code = (error as Record<string, unknown>).code;
  }
  if (code === "invalid_archive") return "invalid_archive";
  if (code === "unsupported_format_version")
    return "unsupported_format_version";
  if (code === "payload_too_large" || status === 413)
    return "payload_too_large";
  if (code === "rate_limited" || status === 429) return "rate_limited";
  return "failed";
}

/** Upload progress as a whole percent, 0 to 100. */
export function uploadPercent(loaded: number, total: number): number {
  if (!(total > 0) || !(loaded > 0)) return 0;
  return Math.min(100, Math.floor((loaded / total) * 100));
}

export interface RestoreUpload {
  promise: Promise<RestoreResult>;
  abort: () => void;
}

export class RestoreError extends Error {
  readonly kind: RestoreErrorKind;
  constructor(kind: RestoreErrorKind) {
    super(kind);
    this.name = "RestoreError";
    this.kind = kind;
  }
}

/**
 * Uploads the archive as the raw request body. XMLHttpRequest is used because
 * fetch has no upload progress. Rejects with RestoreError, or with an
 * AbortError when aborted.
 */
export function uploadRestore(
  file: File,
  csrfToken: string,
  onProgress: (percent: number, done: boolean) => void,
): RestoreUpload {
  const xhr = new XMLHttpRequest();
  const promise = new Promise<RestoreResult>((resolve, reject) => {
    xhr.open("POST", importPath);
    xhr.withCredentials = true;
    xhr.setRequestHeader("accept", "application/json");
    xhr.setRequestHeader("content-type", restoreContentType(file));
    if (csrfToken) xhr.setRequestHeader("x-csrf-token", csrfToken);
    xhr.upload.onprogress = (event) =>
      onProgress(uploadPercent(event.loaded, file.size), false);
    xhr.upload.onload = () => onProgress(100, true);
    xhr.onabort = () => reject(new DOMException("Aborted", "AbortError"));
    xhr.onerror = () => reject(new RestoreError("failed"));
    xhr.onload = () => {
      let body: unknown;
      try {
        body = JSON.parse(xhr.responseText);
      } catch {
        body = undefined;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        const result = parseRestoreResult(body);
        if (result) resolve(result);
        else reject(new RestoreError("failed"));
        return;
      }
      reject(new RestoreError(restoreErrorKind(xhr.status, body)));
    };
    xhr.send(file);
  });
  return { promise, abort: () => xhr.abort() };
}
