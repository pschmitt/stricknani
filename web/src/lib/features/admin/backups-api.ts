import { apiRequest, type SessionData } from "#lib/api.ts";
import { avatarPath } from "#lib/user-label.ts";

/**
 * Client for the administrator backups API (`/api/v2/admin/backups`): the
 * listing, the restore preview and the guarded restore. Downloading backups is
 * deliberately not part of it.
 */

export type BackupKind =
  "nightly" | "predeploy" | "pre_migrate" | "pre_restore" | "manual";

export type MediaOption = "none" | "add_missing";

export interface BackupCounts {
  users: number | null;
  projects: number | null;
  yarns: number | null;
  trashed: number | null;
}

export interface BackupEntry {
  name: string;
  kind: BackupKind;
  /** RFC 3339, UTC. */
  created_at: string;
  size_bytes: number;
  has_media: boolean;
  schema_version: number | null;
  counts: BackupCounts | null;
  readable: boolean;
  newer_schema: boolean;
  restorable: boolean;
  error: string | null;
}

export interface RestoreOutcome {
  status: "succeeded" | "failed";
  backup: string;
  pre_restore: string;
  media: MediaOption;
  finished_at: string;
  requested_by_email: string;
  /** The administrator's avatar path when the account still exists. */
  requested_by_avatar_url?: string | null;
  error: string | null;
  media_added: number | null;
  media_error: string | null;
}

export interface PendingRestore {
  backup: string;
  pre_restore: string;
  requested_at: string;
}

export interface BackupList {
  available: boolean;
  server_schema_version: number;
  backups: BackupEntry[];
  /** The newest restorable `pre-restore-*` file: what "Undo last restore" restores. */
  latest_pre_restore: string | null;
  pending_restore: PendingRestore | null;
  last_restore: RestoreOutcome | null;
  data_epoch: string | null;
  /** The server stops itself after staging a restore and relies on a supervisor. */
  auto_restart: boolean;
}

export interface RestoreSnapshot {
  schema_version: number | null;
  counts: BackupCounts | null;
}

export interface RestorePreview {
  backup: BackupEntry;
  current: RestoreSnapshot;
  backup_snapshot: RestoreSnapshot;
  /** Audit events recorded after the backup was taken; null when unknown. */
  changes_since_backup: number | null;
  media_options: MediaOption[];
  restarts_server: boolean;
}

export interface RestoreAccepted {
  backup: string;
  pre_restore: string;
  restarting: boolean;
}

const KINDS: readonly BackupKind[] = [
  "nightly",
  "predeploy",
  "pre_migrate",
  "pre_restore",
  "manual",
];

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const count = (value: unknown): number | null =>
  Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : null;

const text = (value: unknown): string =>
  typeof value === "string" ? value : "";

const optionalText = (value: unknown): string | null =>
  typeof value === "string" ? value : null;

function invalid(): Error {
  return new Error("The server sent an unexpected backups response.");
}

function parseCounts(value: unknown): BackupCounts | null {
  if (!record(value)) return null;
  return {
    users: count(value.users),
    projects: count(value.projects),
    yarns: count(value.yarns),
    trashed: count(value.trashed),
  };
}

export function parseBackupEntry(value: unknown): BackupEntry {
  if (!record(value) || typeof value.name !== "string") throw invalid();
  const kind = KINDS.find((candidate) => candidate === value.kind) ?? "manual";
  return {
    name: value.name,
    kind,
    created_at: text(value.created_at),
    size_bytes: count(value.size_bytes) ?? 0,
    has_media: value.has_media === true,
    schema_version: count(value.schema_version),
    counts: parseCounts(value.counts),
    readable: value.readable === true,
    newer_schema: value.newer_schema === true,
    restorable: value.restorable === true,
    error: optionalText(value.error),
  };
}

function parseOutcome(value: unknown): RestoreOutcome | null {
  if (!record(value)) return null;
  return {
    status: value.status === "succeeded" ? "succeeded" : "failed",
    backup: text(value.backup),
    pre_restore: text(value.pre_restore),
    media: value.media === "add_missing" ? "add_missing" : "none",
    finished_at: text(value.finished_at),
    requested_by_email: text(value.requested_by_email),
    requested_by_avatar_url: avatarPath(value.requested_by_avatar_url),
    error: optionalText(value.error),
    media_added: count(value.media_added),
    media_error: optionalText(value.media_error),
  };
}

export function parseBackupList(payload: unknown): BackupList {
  if (!record(payload) || !record(payload.data)) throw invalid();
  const data = payload.data;
  if (!Array.isArray(data.backups)) throw invalid();
  const pending = record(data.pending_restore)
    ? {
        backup: text(data.pending_restore.backup),
        pre_restore: text(data.pending_restore.pre_restore),
        requested_at: text(data.pending_restore.requested_at),
      }
    : null;
  return {
    available: data.available === true,
    server_schema_version: count(data.server_schema_version) ?? 0,
    backups: data.backups.map(parseBackupEntry),
    latest_pre_restore: optionalText(data.latest_pre_restore),
    pending_restore: pending,
    last_restore: parseOutcome(data.last_restore),
    data_epoch: optionalText(data.data_epoch),
    auto_restart: data.auto_restart === true,
  };
}

function parseSnapshot(value: unknown): RestoreSnapshot {
  if (!record(value)) return { schema_version: null, counts: null };
  return {
    schema_version: count(value.schema_version),
    counts: parseCounts(value.counts),
  };
}

export function parseRestorePreview(payload: unknown): RestorePreview {
  if (!record(payload) || !record(payload.data)) throw invalid();
  const data = payload.data;
  const options = Array.isArray(data.media_options)
    ? data.media_options.filter(
        (option): option is MediaOption =>
          option === "none" || option === "add_missing",
      )
    : ["none" as const];
  return {
    backup: parseBackupEntry(data.backup),
    current: parseSnapshot(data.current),
    backup_snapshot: parseSnapshot(data.backup_snapshot),
    changes_since_backup: count(data.changes_since_backup),
    media_options: options.length > 0 ? options : ["none"],
    restarts_server: data.restarts_server === true,
  };
}

const base = "/api/v2/admin/backups";

export async function listBackups(signal?: AbortSignal): Promise<BackupList> {
  return parseBackupList(await apiRequest(base, { signal }));
}

export async function previewRestore(
  name: string,
  signal?: AbortSignal,
): Promise<RestorePreview> {
  return parseRestorePreview(
    await apiRequest(`${base}/${encodeURIComponent(name)}/restore-preview`, {
      signal,
    }),
  );
}

/**
 * Ask the server to restore `name`. The server needs a recent
 * re-authentication (`reauth_required` otherwise) and the exact name.
 */
export async function restoreBackup(
  name: string,
  confirmation: string,
  media: MediaOption,
  currentSession: SessionData,
): Promise<RestoreAccepted> {
  const payload = await apiRequest(
    `${base}/${encodeURIComponent(name)}/restore`,
    {
      method: "POST",
      csrfToken: currentSession.csrf_token,
      body: { confirmation, media },
    },
  );
  if (!record(payload) || !record(payload.data)) throw invalid();
  return {
    backup: text(payload.data.backup),
    pre_restore: text(payload.data.pre_restore),
    restarting: payload.data.restarting === true,
  };
}

export type RestartResult =
  | { kind: "succeeded" }
  | { kind: "failed"; error: string | null }
  | { kind: "timeout" };

export interface WaitOptions {
  /** Epoch before the restore; a different one means the data was replaced. */
  previousEpoch: string | null;
  /** When the restore was accepted (ms); older outcomes are ignored. */
  requestedAtMs: number;
  timeoutMs?: number;
  intervalMs?: number;
  fetchImpl?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  now?: () => number;
  signal?: AbortSignal;
}

interface MetaProbe {
  epoch: string | null;
  lastStatus: "succeeded" | "failed" | null;
  lastFinishedMs: number | null;
}

async function probeMeta(fetchImpl: typeof fetch): Promise<MetaProbe | null> {
  try {
    const response = await fetchImpl("/api/v2/meta", {
      credentials: "same-origin",
      headers: { accept: "application/json" },
    });
    if (!response.ok) return null;
    const payload: unknown = await response.json();
    if (!record(payload) || !record(payload.data)) return null;
    const last = record(payload.data.last_restore)
      ? payload.data.last_restore
      : null;
    const finished = last ? Date.parse(text(last.finished_at)) : Number.NaN;
    return {
      epoch: optionalText(payload.data.data_epoch),
      lastStatus:
        last?.status === "succeeded" || last?.status === "failed"
          ? last.status
          : null,
      lastFinishedMs: Number.isFinite(finished) ? finished : null,
    };
  } catch {
    return null;
  }
}

/**
 * Poll `/healthz` and `/api/v2/meta` until the restarted server reports what
 * happened to the restore: a changed data epoch (or a newer "succeeded"
 * outcome) is success, a newer "failed" outcome is a failure. A server that
 * never went down is still the old process, so its state does not count.
 */
export async function waitForRestart(
  options: WaitOptions,
): Promise<RestartResult> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const sleep =
    options.sleep ??
    ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  const now = options.now ?? Date.now;
  const timeoutMs = options.timeoutMs ?? 3 * 60 * 1000;
  const intervalMs = options.intervalMs ?? 1500;
  const deadline = now() + timeoutMs;
  let sawDown = false;
  while (now() < deadline) {
    if (options.signal?.aborted) return { kind: "timeout" };
    let healthy = false;
    try {
      const response = await fetchImpl("/healthz", { cache: "no-store" });
      healthy = response.ok;
    } catch {
      healthy = false;
    }
    if (!healthy) {
      sawDown = true;
    } else if (sawDown) {
      const meta = await probeMeta(fetchImpl);
      if (meta) {
        const outcomeIsNew =
          meta.lastFinishedMs !== null &&
          meta.lastFinishedMs >= options.requestedAtMs - 5000;
        if (
          (meta.epoch !== null &&
            options.previousEpoch !== null &&
            meta.epoch !== options.previousEpoch) ||
          (outcomeIsNew && meta.lastStatus === "succeeded")
        )
          return { kind: "succeeded" };
        if (outcomeIsNew && meta.lastStatus === "failed")
          return { kind: "failed", error: null };
        // Back up with nothing recorded: the restore was not applied.
        return { kind: "failed", error: null };
      }
    }
    await sleep(intervalMs);
  }
  return { kind: "timeout" };
}
