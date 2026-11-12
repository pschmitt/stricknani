import { ApiError, apiRequest } from "#lib/api.ts";
import {
  parseAuditMedia,
  parseChangeSet,
  parseImpersonator,
  type AuditMedia,
  type ChangeSetV1,
  type ChangeUnavailableReason,
  type Impersonator,
  type PageMeta,
} from "#lib/features/admin/admin-api.ts";

import { avatarPath } from "#lib/user-label.ts";

import { parseChangeMedia, type ChangeMedia } from "./media-change";

/** The two resources that keep an owner-visible change history. */
export type HistoryKind = "projects" | "yarns";

export interface HistoryEvent {
  id: number;
  /** Only readers who manage the resource get it; show `actor_name`, never this, in the UI. */
  actor_email: string | null;
  /** The actor's display name (never an email); absent without an account. */
  actor_name?: string | null;
  actor_avatar_url: string | null;
  /** The reader is the actor. */
  actor_is_self?: boolean;
  entity_type: string;
  entity_id: number;
  action: string;
  created_at: string;
  has_changes: boolean;
  media: AuditMedia | null;
  /** Thumbnails of the pictures named by this event's picture changes, keyed by label. */
  media_thumbnails: ChangeMedia | null;
  /** The administrator who made this change while acting as the actor. */
  impersonated_by: Impersonator | null;
  /**
   * The person of a sharing event (shared, share_role_changed, share_revoked, share_left,
   * ownership_transferred): `id`/`name` are null once that account was deleted; `role` is the
   * share role, absent for transfers.
   */
  subject?: HistorySubject | null;
  /** True only for the automatic purge of the trash: no actor. */
  system?: boolean;
}

export interface HistorySubject {
  id: number | null;
  name: string | null;
  role: string | null;
  avatar_url?: string | null;
}

export interface HistoryPage {
  data: HistoryEvent[];
  meta: PageMeta;
}

export interface HistoryDetail {
  event: HistoryEvent;
  changes: ChangeSetV1 | null;
  unavailable_reason: ChangeUnavailableReason | null;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalid(): ApiError {
  return new ApiError("Unexpected history response", 200, "invalid_response");
}

export function parseHistoryEvent(value: unknown): HistoryEvent {
  if (
    !record(value) ||
    typeof value.id !== "number" ||
    !Number.isSafeInteger(value.id) ||
    value.id <= 0 ||
    typeof value.action !== "string" ||
    typeof value.created_at !== "string" ||
    typeof value.entity_type !== "string" ||
    typeof value.entity_id !== "number" ||
    (value.actor_email !== null && typeof value.actor_email !== "string")
  )
    throw invalid();
  const avatar = value.actor_avatar_url;
  return {
    id: value.id,
    actor_email: value.actor_email,
    actor_name:
      typeof value.actor_name === "string" && value.actor_name.trim() !== ""
        ? value.actor_name
        : null,
    // Only same-origin paths are rendered as images.
    actor_avatar_url: avatarPath(avatar),
    actor_is_self: value.actor_is_self === true,
    entity_type: value.entity_type,
    entity_id: value.entity_id,
    action: value.action,
    created_at: value.created_at,
    has_changes: value.has_changes === true,
    media: parseAuditMedia(value.media),
    media_thumbnails: parseChangeMedia(value.media_thumbnails),
    impersonated_by: parseImpersonator(value.impersonated_by),
    subject: parseSubject(value.subject),
    system: value.system === true,
  };
}

function parseSubject(value: unknown): HistorySubject | null {
  if (!record(value)) return null;
  const id = value.id;
  return {
    id: typeof id === "number" && Number.isSafeInteger(id) ? id : null,
    name: typeof value.name === "string" ? value.name : null,
    role: typeof value.role === "string" ? value.role : null,
    avatar_url: avatarPath(value.avatar_url),
  };
}

export function parseHistoryPage(payload: unknown): HistoryPage {
  if (!record(payload) || !Array.isArray(payload.data)) throw invalid();
  const meta = payload.meta;
  if (
    !record(meta) ||
    typeof meta.page !== "number" ||
    typeof meta.per_page !== "number" ||
    typeof meta.has_more !== "boolean"
  )
    throw invalid();
  return {
    data: payload.data.map(parseHistoryEvent),
    meta: { page: meta.page, per_page: meta.per_page, has_more: meta.has_more },
  };
}

export function parseHistoryDetail(payload: unknown): HistoryDetail {
  if (!record(payload) || !record(payload.data)) throw invalid();
  const data = payload.data;
  const reason = data.unavailable_reason;
  return {
    event: parseHistoryEvent(data.event),
    changes: data.changes == null ? null : parseChangeSet(data.changes),
    unavailable_reason:
      reason === "not_recorded" ||
      reason === "malformed" ||
      reason === "too_large"
        ? reason
        : null,
  };
}

/** True when the server has no history endpoint (older server) and the section should hide. */
export function historyUnsupported(error: unknown): boolean {
  return (
    error instanceof ApiError && (error.status === 404 || error.status === 405)
  );
}

export async function listHistory(
  kind: HistoryKind,
  id: number,
  page: number,
  signal?: AbortSignal,
): Promise<HistoryPage> {
  return parseHistoryPage(
    await apiRequest(`/api/v2/${kind}/${id}/history?page=${page}`, { signal }),
  );
}

export async function getHistoryDetail(
  kind: HistoryKind,
  id: number,
  eventId: number,
  signal?: AbortSignal,
): Promise<HistoryDetail> {
  return parseHistoryDetail(
    await apiRequest(`/api/v2/${kind}/${id}/history/${eventId}`, { signal }),
  );
}
