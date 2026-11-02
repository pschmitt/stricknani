import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export interface AdminUser {
  id: number;
  email: string;
  active: boolean;
  is_admin: boolean;
  avatar_url: string | null;
}

export interface AuditEvent {
  id: number;
  actor_email: string | null;
  actor_avatar_url: string | null;
  legacy_actor_user_id: number | null;
  entity_type: string;
  entity_id: number;
  action: string;
  created_at: string;
}

export interface PageMeta {
  page: number;
  per_page: number;
  has_more: boolean;
}

export interface AdminPage {
  data: AdminUser[];
  meta: PageMeta;
}

export interface AuditPage {
  data: AuditEvent[];
  meta: PageMeta;
}

export type AuditEntity = "all" | "project" | "yarn" | "category";

export interface AuditFilters {
  page: number;
  entity: AuditEntity;
  action: string;
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const positiveInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;

const safeInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value);

const adminAvatarPattern =
  /^\/api\/v2\/admin\/users\/(\d+)\/avatar\/thumb_[A-Za-z0-9._-]+\.jpg$/;

function validAdminAvatarUrl(
  value: unknown,
  userId?: number,
): value is string | null {
  if (value === null) return true;
  if (typeof value !== "string") return false;
  const match = adminAvatarPattern.exec(value);
  if (!match) return false;
  const id = Number(match[1]);
  return positiveInteger(id) && (userId === undefined || id === userId);
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    200,
    "invalid_response",
  );
}

function parseMeta(value: unknown): PageMeta {
  if (
    !record(value) ||
    !positiveInteger(value.page) ||
    value.page > 10000 ||
    !positiveInteger(value.per_page) ||
    value.per_page !== 50 ||
    typeof value.has_more !== "boolean"
  )
    throw invalidResponse();
  return {
    page: value.page,
    per_page: value.per_page,
    has_more: value.has_more,
  };
}

function parseUser(value: unknown): AdminUser {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    typeof value.email !== "string" ||
    typeof value.active !== "boolean" ||
    typeof value.is_admin !== "boolean" ||
    !validAdminAvatarUrl(value.avatar_url, value.id)
  )
    throw invalidResponse();
  return {
    id: value.id,
    email: value.email,
    active: value.active,
    is_admin: value.is_admin,
    avatar_url: value.avatar_url,
  };
}

function parseAuditEvent(value: unknown): AuditEvent {
  if (
    !record(value) ||
    Object.hasOwn(value, "details") ||
    !positiveInteger(value.id) ||
    (value.actor_email !== null && typeof value.actor_email !== "string") ||
    !validAdminAvatarUrl(value.actor_avatar_url) ||
    (value.legacy_actor_user_id !== null &&
      !safeInteger(value.legacy_actor_user_id)) ||
    typeof value.entity_type !== "string" ||
    !safeInteger(value.entity_id) ||
    typeof value.action !== "string" ||
    typeof value.created_at !== "string"
  )
    throw invalidResponse();
  return {
    id: value.id,
    actor_email: value.actor_email,
    actor_avatar_url: value.actor_avatar_url,
    legacy_actor_user_id: value.legacy_actor_user_id,
    entity_type: value.entity_type,
    entity_id: value.entity_id,
    action: value.action,
    created_at: value.created_at,
  };
}

export function auditFiltersFromSearch(search: string): AuditFilters {
  const params = new URLSearchParams(search);
  const page = Number(params.get("page") ?? "1");
  const entity = params.get("entity");
  return {
    page: Number.isSafeInteger(page) && page >= 1 && page <= 10000 ? page : 1,
    entity:
      entity === "project" || entity === "yarn" || entity === "category"
        ? entity
        : "all",
    action: params.get("action") ?? "",
  };
}

export function auditSearchFromFilters(filters: AuditFilters): string {
  const params = new URLSearchParams();
  if (filters.page > 1) params.set("page", String(filters.page));
  if (filters.entity !== "all") params.set("entity", filters.entity);
  const action = filters.action.trim();
  if (action) params.set("action", action);
  return params.toString();
}

export async function listAdminUsers(
  page: number,
  signal?: AbortSignal,
): Promise<AdminPage> {
  const payload: unknown = await apiRequest(
    `/api/v2/admin/users?page=${Math.min(Math.max(page, 1), 10000)}`,
    { signal },
  );
  if (!record(payload) || !Array.isArray(payload.data)) throw invalidResponse();
  return { data: payload.data.map(parseUser), meta: parseMeta(payload.meta) };
}

export async function createAdminUser(
  email: string,
  password: string,
  isAdmin: boolean,
  currentSession: SessionData,
): Promise<AdminUser> {
  const payload: unknown = await apiRequest("/api/v2/admin/users", {
    method: "POST",
    csrfToken: currentSession.csrf_token,
    body: { email, password, is_admin: isAdmin },
  });
  if (!record(payload)) throw invalidResponse();
  return parseUser(payload.data);
}

export async function setUserActive(
  id: number,
  active: boolean,
  currentSession: SessionData,
): Promise<AdminUser> {
  const payload: unknown = await apiRequest(
    `/api/v2/admin/users/${id}/active`,
    {
      method: "PATCH",
      csrfToken: currentSession.csrf_token,
      body: { active },
    },
  );
  if (!record(payload)) throw invalidResponse();
  return parseUser(payload.data);
}

export async function resetUserPassword(
  id: number,
  newPassword: string,
  currentSession: SessionData,
): Promise<{ id: number; session_revoked: true }> {
  const payload: unknown = await apiRequest(
    `/api/v2/admin/users/${id}/reset-password`,
    {
      method: "POST",
      csrfToken: currentSession.csrf_token,
      body: { new_password: newPassword },
    },
  );
  if (
    !record(payload) ||
    !record(payload.data) ||
    payload.data.id !== id ||
    payload.data.session_revoked !== true
  )
    throw invalidResponse();
  return { id, session_revoked: true };
}

export async function listAuditEvents(
  filters: AuditFilters,
  signal?: AbortSignal,
): Promise<AuditPage> {
  const query = auditSearchFromFilters(filters);
  const payload: unknown = await apiRequest(
    `/api/v2/admin/audit${query ? `?${query}` : ""}`,
    { signal },
  );
  if (!record(payload) || !Array.isArray(payload.data)) throw invalidResponse();
  return {
    data: payload.data.map(parseAuditEvent),
    meta: parseMeta(payload.meta),
  };
}
