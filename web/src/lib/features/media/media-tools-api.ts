import { apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export type OcrLanguage = "deu+eng" | "deu" | "eng";
export type ArchiveEntity = "projects" | "yarns";
export type ArchiveState =
  "pending" | "running" | "archived" | "failed" | "superseded" | null;

export interface ArchiveStatus {
  state: ArchiveState;
  updated_at: number | null;
}

export interface OcrResult {
  text: string;
}

/** Ten bounded checks with pauses totaling 119 seconds while the page is visible. */
export const ARCHIVE_POLL_DELAYS_MS = [
  2_000, 4_000, 8_000, 15_000, 15_000, 15_000, 15_000, 15_000, 15_000, 15_000,
] as const;
export const ARCHIVE_POLL_DEADLINE_MS = 120_000;

export function canManuallyCheckArchive(
  status: ArchiveStatus | null,
  pollingExhausted: boolean,
): boolean {
  return (
    pollingExhausted &&
    (status?.state === "pending" || status?.state === "running")
  );
}

export async function pollArchiveStatus(options: {
  controller: AbortController;
  readStatus: (signal: AbortSignal) => Promise<ArchiveStatus>;
  onStatus: (status: ArchiveStatus) => void | Promise<void>;
  onDeadline: () => void;
  wait: (delayMs: number, signal: AbortSignal) => Promise<void>;
}): Promise<void> {
  const { controller, readStatus, onStatus, onDeadline, wait } = options;
  const deadlineTimer = globalThis.setTimeout(() => {
    if (controller.signal.aborted) return;
    onDeadline();
    controller.abort();
  }, ARCHIVE_POLL_DEADLINE_MS);

  try {
    for (const delay of ARCHIVE_POLL_DELAYS_MS) {
      try {
        await wait(delay, controller.signal);
      } catch (error) {
        if (controller.signal.aborted) return;
        throw error;
      }
      if (controller.signal.aborted) return;

      let status: ArchiveStatus;
      try {
        status = await readStatus(controller.signal);
      } catch (error) {
        if (controller.signal.aborted) return;
        throw error;
      }
      if (controller.signal.aborted) return;

      try {
        await onStatus(status);
      } catch (error) {
        if (controller.signal.aborted) return;
        throw error;
      }
      if (controller.signal.aborted || !isArchiveActive(status)) return;
    }
    onDeadline();
    controller.abort();
  } finally {
    globalThis.clearTimeout(deadlineTimer);
  }
}

export async function recognizeImageText(
  src: string,
  lang: OcrLanguage,
  currentSession: SessionData,
  signal: AbortSignal,
): Promise<OcrResult> {
  const payload: unknown = await apiRequest("/api/v2/utils/ocr", {
    method: "POST",
    csrfToken: currentSession.csrf_token,
    body: { src, lang, force: false },
    signal,
  });
  if (!isRecord(payload) || !isRecord(payload.data))
    throw new Error(t("Text recognition returned an unreadable response."));
  const text = payload.data.text;
  if (typeof text !== "string")
    throw new Error(t("Text recognition returned an unreadable response."));
  return { text };
}

export async function getArchiveStatus(
  entity: ArchiveEntity,
  id: number,
  signal: AbortSignal,
): Promise<ArchiveStatus> {
  const payload: unknown = await apiRequest(
    `/api/v2/${entity}/${positiveId(id)}/archive`,
    { signal },
  );
  return parseArchiveStatus(payload);
}

export async function requestArchive(
  entity: ArchiveEntity,
  id: number,
  currentSession: SessionData,
  signal: AbortSignal,
): Promise<ArchiveStatus> {
  const payload: unknown = await apiRequest(
    `/api/v2/${entity}/${positiveId(id)}/archive`,
    {
      method: "POST",
      csrfToken: currentSession.csrf_token,
      body: {},
      signal,
    },
  );
  return parseArchiveStatus(payload);
}

export function parseArchiveStatus(payload: unknown): ArchiveStatus {
  if (!isRecord(payload) || !isRecord(payload.data))
    throw new Error(t("Archive status returned an unreadable response."));
  const state = payload.data.state;
  const updatedAt = payload.data.updated_at ?? null;
  if (
    (state !== null &&
      state !== "pending" &&
      state !== "running" &&
      state !== "archived" &&
      state !== "failed" &&
      state !== "superseded") ||
    (updatedAt !== null &&
      (!Number.isSafeInteger(updatedAt) || Number(updatedAt) < 0)) ||
    (state === null && updatedAt !== null)
  ) {
    throw new Error(t("Archive status returned an unreadable response."));
  }
  return { state, updated_at: updatedAt as number | null };
}

function positiveId(id: number): number {
  if (!Number.isSafeInteger(id) || id <= 0)
    throw new Error(t("That item could not be found."));
  return id;
}

function isArchiveActive(status: ArchiveStatus): boolean {
  return status.state === "pending" || status.state === "running";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
