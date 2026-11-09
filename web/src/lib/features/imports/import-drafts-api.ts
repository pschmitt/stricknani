import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t, type Locale } from "#lib/i18n.svelte.ts";
import {
  parseImportPreview,
  type ImportPreview,
  type ImportTarget,
} from "./import-api";

export type ImportDraftStatus = "running" | "ready" | "failed";

/** A server-side import job: it runs while the user is away and waits for review. */
export interface ImportDraft {
  id: number;
  target: ImportTarget;
  source_kind: "url" | "text";
  source_url: string | null;
  use_ai: boolean;
  status: ImportDraftStatus;
  title: string | null;
  image_url: string | null;
  /** Unix seconds. */
  created_at: number;
  finished_at: number | null;
  error_status: number | null;
  error: { code: string; message: string } | null;
  /** Only on a single draft that is ready. */
  preview: ImportPreview | null;
}

export type ImportDraftSource =
  | { type: "url"; url: string; use_ai: boolean; target: ImportTarget }
  | { type: "text"; text: string; use_ai: boolean };

/** How often a running import is looked at again. */
export const IMPORT_DRAFT_POLL_MS = 2000;

export async function createImportDraft(
  source: ImportDraftSource,
  locale: Locale,
  session: SessionData,
  signal?: AbortSignal,
): Promise<ImportDraft> {
  const payload: unknown = await apiRequest("/api/v2/imports/drafts", {
    method: "POST",
    csrfToken: session.csrf_token,
    body: { ...source, locale },
    signal,
  });
  return parseImportDraftEnvelope(payload);
}

export async function listImportDrafts(
  signal?: AbortSignal,
): Promise<ImportDraft[]> {
  const payload: unknown = await apiRequest("/api/v2/imports/drafts", {
    signal,
  });
  if (!isRecord(payload) || !Array.isArray(payload.data))
    throw invalidResponse();
  return payload.data.map(parseImportDraft);
}

export async function getImportDraft(
  id: number,
  signal?: AbortSignal,
): Promise<ImportDraft> {
  const payload: unknown = await apiRequest(`/api/v2/imports/drafts/${id}`, {
    signal,
  });
  return parseImportDraftEnvelope(payload);
}

export async function deleteImportDraft(
  id: number,
  session: SessionData,
  signal?: AbortSignal,
): Promise<void> {
  await apiRequest(`/api/v2/imports/drafts/${id}`, {
    method: "DELETE",
    csrfToken: session.csrf_token,
    signal,
  });
}

/** The draft id of `?draft=`, or null. */
export function draftIdFromSearch(search: string): number | null {
  const value = new URLSearchParams(search).get("draft");
  if (!value || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/** Why a draft failed (the server's own wording), or a generic line. */
export function draftFailureMessage(
  draft: ImportDraft,
  locale: Locale,
): string {
  return (
    draft.error?.message ||
    t("The import did not work. Try again or add it by hand.", locale)
  );
}

/** A friendly line for a refused or failed start of an import. */
export function importStartMessage(error: unknown, locale: Locale): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "too_many_imports":
        return t(
          "Three imports are already running. Wait until one is done.",
          locale,
        );
      case "too_many_drafts":
        return t(
          "Too many imports are waiting for review. Remove some first.",
          locale,
        );
      case "feature_disabled":
        return t("Imports are turned off on this server.", locale);
      case "rate_limited": {
        const wait = error.retryAfterSeconds;
        return wait === undefined
          ? t("Too many requests. Wait a moment, then try again.", locale)
          : t(
              "Too many requests. Try again in {seconds} seconds.",
              locale,
            ).replace("{seconds}", String(wait));
      }
    }
    if (error.status === 429)
      return t("Too many requests. Wait a moment, then try again.", locale);
    if (error.message) return error.message;
  }
  return error instanceof Error
    ? error.message
    : t("The import did not work. Try again or add it by hand.", locale);
}

function parseImportDraftEnvelope(payload: unknown): ImportDraft {
  if (!isRecord(payload)) throw invalidResponse();
  return parseImportDraft(payload.data);
}

export function parseImportDraft(value: unknown): ImportDraft {
  if (
    !isRecord(value) ||
    !isCount(value.id) ||
    (value.target !== "project" && value.target !== "yarn") ||
    (value.status !== "running" &&
      value.status !== "ready" &&
      value.status !== "failed")
  )
    throw invalidResponse();
  // The error is the preview endpoint's body: `{ error: { code, message } }`.
  const error = isRecord(value.error)
    ? isRecord(value.error.error)
      ? value.error.error
      : value.error
    : null;
  return {
    id: value.id,
    target: value.target,
    source_kind: value.source_kind === "text" ? "text" : "url",
    source_url: text(value.source_url),
    use_ai: value.use_ai === true,
    status: value.status,
    title: text(value.title),
    image_url: text(value.image_url),
    created_at: typeof value.created_at === "number" ? value.created_at : 0,
    finished_at:
      typeof value.finished_at === "number" ? value.finished_at : null,
    error_status:
      typeof value.error_status === "number" ? value.error_status : null,
    error:
      error && typeof error.message === "string"
        ? {
            code: typeof error.code === "string" ? error.code : "",
            message: error.message,
          }
        : null,
    preview: isRecord(value.preview)
      ? parseImportPreview({ data: value.preview })
      : null,
  };
}

function text(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function isCount(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected import response."),
    200,
    "invalid_response",
  );
}
