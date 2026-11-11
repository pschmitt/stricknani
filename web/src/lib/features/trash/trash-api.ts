import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";
import { parseOwner, type ResourceOwner } from "#lib/owner.ts";
import {
  isTrashKind,
  trashSegment,
  type TrashKind,
  type TrashParentKind,
} from "./trash-kinds";

export type TrashScope = "mine" | "all";

export interface TrashParent {
  kind: TrashParentKind;
  id: number;
  name: string;
}

export interface TrashItem {
  kind: TrashKind;
  id: number;
  name: string;
  owner: ResourceOwner | null;
  /** Whether the caller may restore or delete the item (its owner and administrators). */
  can_restore: boolean;
  deleted_at: string;
  purge_at: string;
  /** Whole days left, rounded up; 0 when the item is due for deletion. */
  days_remaining: number;
  parent: TrashParent | null;
  detail: string | null;
  /** Cover or picture of the item (a same-origin media path); null for kinds without one. */
  thumbnail_url: string | null;
}

export interface TrashList {
  items: TrashItem[];
  scope: TrashScope;
  retention_days: number;
}

export const DEFAULT_RETENTION_DAYS = 90;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function positive(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    200,
    "invalid_response",
  );
}

function parseParent(value: unknown): TrashParent | null {
  if (!record(value)) return null;
  if (
    (value.kind !== "project" && value.kind !== "yarn") ||
    !positive(value.id) ||
    typeof value.name !== "string"
  )
    return null;
  return { kind: value.kind, id: value.id, name: value.name };
}

export function parseTrashItem(value: unknown): TrashItem {
  if (
    !record(value) ||
    !isTrashKind(value.kind) ||
    !positive(value.id) ||
    typeof value.name !== "string" ||
    typeof value.deleted_at !== "string" ||
    typeof value.purge_at !== "string" ||
    typeof value.days_remaining !== "number" ||
    !Number.isSafeInteger(value.days_remaining) ||
    value.days_remaining < 0
  )
    throw invalidResponse();
  return {
    kind: value.kind,
    id: value.id,
    name: value.name,
    owner: parseOwner(value.owner),
    can_restore: value.can_restore !== false,
    deleted_at: value.deleted_at,
    purge_at: value.purge_at,
    days_remaining: value.days_remaining,
    parent: parseParent(value.parent),
    detail:
      typeof value.detail === "string" && value.detail.trim() !== ""
        ? value.detail
        : null,
    thumbnail_url:
      typeof value.thumbnail_url === "string" &&
      (value.thumbnail_url.startsWith("/media/") ||
        value.thumbnail_url.startsWith("/api/v2/trash/"))
        ? value.thumbnail_url
        : null,
  };
}

export function parseTrashList(payload: unknown): TrashList {
  if (!record(payload) || !Array.isArray(payload.data)) throw invalidResponse();
  const meta = record(payload.meta) ? payload.meta : {};
  return {
    items: payload.data.map(parseTrashItem),
    scope: meta.scope === "all" ? "all" : "mine",
    retention_days: positive(meta.retention_days)
      ? meta.retention_days
      : DEFAULT_RETENTION_DAYS,
  };
}

export async function listTrash(
  scope: TrashScope,
  signal?: AbortSignal,
): Promise<TrashList> {
  const query = new URLSearchParams({ scope });
  return parseTrashList(
    await apiRequest(`/api/v2/trash?${query.toString()}`, { signal }),
  );
}

function itemPath(kind: TrashKind, id: number): string {
  return `/api/v2/trash/${trashSegment(kind)}/${id}`;
}

export async function restoreTrashItem(
  kind: TrashKind,
  id: number,
  session: SessionData,
  signal?: AbortSignal,
): Promise<void> {
  await apiRequest(`${itemPath(kind, id)}/restore`, {
    method: "POST",
    csrfToken: session.csrf_token,
    signal,
  });
}

export async function purgeTrashItem(
  kind: TrashKind,
  id: number,
  session: SessionData,
  signal?: AbortSignal,
): Promise<void> {
  await apiRequest(itemPath(kind, id), {
    method: "DELETE",
    csrfToken: session.csrf_token,
    signal,
  });
}

/** Where a restored item can be opened, or null when it has no page of its own. */
export function restoredTarget(
  kind: TrashKind,
  id: number,
  parent: TrashParent | null,
): { kind: "project" | "yarn"; id: number } | null {
  if (kind === "project" || kind === "yarn") return { kind, id };
  if (parent) return { kind: parent.kind, id: parent.id };
  return null;
}
