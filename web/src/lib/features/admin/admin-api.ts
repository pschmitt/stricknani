import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";
import { parseAuditClient, type AuditClient } from "./audit-client";
import {
  parseChangeMedia,
  type ChangeMedia,
} from "#lib/features/history/media-change.ts";
import {
  parseLifecycleSubject,
  parseResourceState,
  parseTrashStatus,
  type LifecycleSubject,
  type ResourceState,
  type TrashStatus,
} from "#lib/features/trash/trash-state.ts";

export interface AdminUser {
  id: number;
  email: string;
  active: boolean;
  is_admin: boolean;
  avatar_url: string | null;
  /** Count of linked OIDC identities; absent on older servers. */
  linked_identities?: number;
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
  resource: AuditResourceReference;
  has_changes: boolean;
  /** The picture or file an upload/removal event was about. */
  media: AuditMedia | null;
  /** Thumbnails of the pictures named by picture changes, by label (read-only administrator URLs). */
  media_thumbnails: ChangeMedia | null;
  /** The administrator who made this change while acting as the actor. */
  impersonated_by: Impersonator | null;
  /** True only for the automatic 90-day purge of the trash: no actor. */
  system: boolean;
  /** The owner of the resource, named on events an administrator or the system caused. */
  subject: LifecycleSubject | null;
  /** The client that caused the event; `unknown` for events recorded before clients were kept. */
  client: AuditClient;
  /** What a server event (a backup restore) was about; absent on other events. */
  server?: AuditServerEvent | null;
}

export interface Impersonator {
  id: number | null;
  name: string | null;
  email: string | null;
}

/** The optional `impersonated_by` of an audit or history event; malformed values read as none. */
export function parseImpersonator(value: unknown): Impersonator | null {
  if (!record(value)) return null;
  return {
    id: safeInteger(value.id) ? value.id : null,
    name: typeof value.name === "string" ? value.name : null,
    email: typeof value.email === "string" ? value.email : null,
  };
}

export interface AuditServerEvent {
  backup: string;
  pre_restore: string;
  media: "none" | "add_missing";
  /** The requesting administrator, also when that account is not in the restored data. */
  actor_email: string | null;
}

/** The `server` block of a backup-restore event; malformed values read as none. */
export function parseAuditServerEvent(value: unknown): AuditServerEvent | null {
  if (
    !record(value) ||
    typeof value.backup !== "string" ||
    typeof value.pre_restore !== "string"
  )
    return null;
  return {
    backup: value.backup,
    pre_restore: value.pre_restore,
    media: value.media === "add_missing" ? "add_missing" : "none",
    actor_email:
      typeof value.actor_email === "string" ? value.actor_email : null,
  };
}

export type AuditMediaKind = "image" | "photo" | "attachment";

export interface AuditMedia {
  kind: AuditMediaKind;
  id: number;
  name: string | null;
  /** False once the file has been deleted; then there is no url. */
  exists: boolean;
  is_image: boolean;
  content_type: string | null;
  size_bytes: number | null;
  url: string | null;
  thumbnail_url: string | null;
}

export type AuditResourceKind = "project" | "yarn" | "category";

export interface AuditResourceReference {
  kind: AuditResourceKind | null;
  id: number;
  name: string | null;
  name_source: "event" | "current" | "unavailable";
  href: string | null;
  preview_url: string | null;
  /** False while the resource is in the trash or gone. */
  exists: boolean;
  /** Where the resource is now; null on servers that predate the trash. */
  state: ResourceState | null;
  /** Present only while the resource is in the trash. */
  trash: TrashStatus | null;
}

export interface ChangeStep {
  number: number;
  title: string;
  description: string | null;
}

export type ChangeValue =
  | { type: "text"; value: string | null }
  | { type: "text_list"; value: string[] }
  | { type: "integer"; value: number | null }
  | { type: "boolean"; value: boolean }
  | { type: "steps"; value: ChangeStep[] }
  | { type: "yarns"; value: string[] }
  | { type: "link_changed"; value: boolean }
  | { type: "legacy_text"; value: string };

export interface ChangeItemV1 {
  field: string;
  before: ChangeValue;
  after: ChangeValue;
}

export interface ChangeSetV1 {
  version: 1;
  items: ChangeItemV1[];
}

export type ChangeUnavailableReason =
  "not_recorded" | "malformed" | "too_large";

export interface AuditEventDetail {
  event: AuditEvent;
  changes: ChangeSetV1 | null;
  unavailable_reason: ChangeUnavailableReason | null;
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

/** The account an audit list is filtered to. */
export interface AuditFilterUser {
  id: number;
  email: string;
}

export interface AuditPage {
  data: AuditEvent[];
  meta: PageMeta;
  /** Set when the list was filtered with `user`. */
  user: AuditFilterUser | null;
}

export type AuditEntity = "all" | "project" | "yarn" | "category" | "server";

export interface AuditFilters {
  page: number;
  entity: AuditEntity;
  action: string;
  /** Show only events this account took part in: as actor, subject or impersonator. */
  user: number | null;
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const positiveInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;

const safeInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value);

const adminAvatarPattern =
  /^\/api\/v2\/admin\/users\/(\d+)\/avatar\/(?:thumb_[A-Za-z0-9._-]+\.jpg|gravatar)$/;

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

export function parseMeta(value: unknown): PageMeta {
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
    !validAdminAvatarUrl(value.avatar_url, value.id) ||
    (value.linked_identities !== undefined &&
      !(
        Number.isSafeInteger(value.linked_identities) &&
        (value.linked_identities as number) >= 0
      ))
  )
    throw invalidResponse();
  return {
    id: value.id,
    email: value.email,
    active: value.active,
    is_admin: value.is_admin,
    avatar_url: value.avatar_url,
    ...(value.linked_identities === undefined
      ? {}
      : { linked_identities: value.linked_identities as number }),
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
    typeof value.created_at !== "string" ||
    typeof value.has_changes !== "boolean"
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
    resource: parseAuditResourceReference(value.resource, value),
    has_changes: value.has_changes,
    media: parseAuditMedia(value.media),
    media_thumbnails: parseChangeMedia(value.media_thumbnails),
    impersonated_by: parseImpersonator(value.impersonated_by),
    system: value.system === true,
    subject: parseLifecycleSubject(value.subject),
    client: parseAuditClient(value.client),
    server: parseAuditServerEvent(value.server),
  };
}

function validMediaUrl(value: unknown): value is string | null {
  if (value === null) return true;
  return (
    typeof value === "string" &&
    value.length <= 512 &&
    (value.startsWith("/api/v2/admin/resources/") ||
      value.startsWith("/media/")) &&
    !value.includes("..")
  );
}

/** Same-origin media links only; a malformed media block never hides the event. */
export function parseAuditMedia(value: unknown): AuditMedia | null {
  if (
    !record(value) ||
    !(
      value.kind === "image" ||
      value.kind === "photo" ||
      value.kind === "attachment"
    ) ||
    !positiveInteger(value.id) ||
    !(value.name === null || typeof value.name === "string") ||
    typeof value.exists !== "boolean" ||
    typeof value.is_image !== "boolean" ||
    !(value.content_type === null || typeof value.content_type === "string") ||
    !(value.size_bytes === null || safeInteger(value.size_bytes)) ||
    !validMediaUrl(value.url) ||
    !validMediaUrl(value.thumbnail_url)
  )
    return null;
  return {
    kind: value.kind,
    id: value.id,
    name: value.name,
    exists: value.exists && value.url !== null,
    is_image: value.is_image,
    content_type: value.content_type,
    size_bytes: value.size_bytes,
    url: value.url,
    thumbnail_url: value.thumbnail_url,
  };
}

function parseAuditResourceReference(
  value: unknown,
  event: Record<string, unknown>,
): AuditResourceReference {
  if (!record(value)) throw invalidResponse();
  const state = parseResourceState(value.state, invalidResponse);
  const trash = parseTrashStatus(value.trash, invalidResponse);
  // A resource in the trash has no page and no media to open: never link or preview it.
  const inTrash = state === "in_trash";
  const href = inTrash ? null : value.href;
  const previewUrl = inTrash ? null : value.preview_url;
  if (
    !(
      value.kind === null ||
      value.kind === "project" ||
      value.kind === "yarn" ||
      value.kind === "category"
    ) ||
    !safeInteger(value.id) ||
    !(value.name === null || typeof value.name === "string") ||
    (value.name_source !== "event" &&
      value.name_source !== "current" &&
      value.name_source !== "unavailable") ||
    !(href === null || typeof href === "string") ||
    !validAuditPreviewUrl(
      previewUrl,
      value.kind,
      value.id,
      href,
      value.exists,
    ) ||
    typeof value.exists !== "boolean"
  )
    throw invalidResponse();

  if (href !== null) {
    const resourceHrefs =
      value.kind !== null && value.exists && value.id > 0
        ? [
            `/app/admin/resources/${value.kind}/${value.id}`,
            ...(value.kind === "project"
              ? [`/app/projects/${value.id}`]
              : value.kind === "yarn"
                ? [`/app/yarns/${value.id}`]
                : []),
          ]
        : [];
    const eventHref = `/app/admin/audit/${event.id}`;
    if (
      !resourceHrefs.includes(href as string) &&
      !(href === eventHref && !value.exists)
    ) {
      throw invalidResponse();
    }
  }
  if (value.kind !== null && value.id !== event.entity_id)
    throw invalidResponse();
  return {
    kind: value.kind,
    id: value.id,
    name: value.name,
    name_source: value.name_source,
    href,
    preview_url: previewUrl,
    exists: value.exists,
    state,
    trash,
  };
}

function validAuditPreviewUrl(
  value: unknown,
  kind: AuditResourceKind | null,
  id: number,
  href: unknown,
  exists: unknown,
): value is string | null {
  if (value === null) return true;
  if (
    typeof value !== "string" ||
    exists !== true ||
    id <= 0 ||
    kind === null ||
    !(
      href === `/app/admin/resources/${kind}/${id}` ||
      href === `/app/projects/${id}` ||
      href === `/app/yarns/${id}`
    )
  )
    return false;
  const mediaKind =
    kind === "project" ? "image" : kind === "yarn" ? "photo" : null;
  if (mediaKind === null) return false;
  const prefix = `/api/v2/admin/resources/${kind}/${id}/media/${mediaKind}/`;
  if (!value.startsWith(prefix) || !value.endsWith("/thumbnail")) return false;
  const mediaId = value.slice(prefix.length, -"/thumbnail".length);
  return (
    positiveInteger(Number(mediaId)) && String(Number(mediaId)) === mediaId
  );
}

const changeFields = new Set([
  "project.name",
  "project.category",
  "project.yarn",
  "project.needles",
  "project.stitch_sample",
  "project.description",
  "project.notes",
  "project.other_materials",
  "project.tags",
  "project.yarns",
  "project.steps",
  "project.step_count",
  "project.image_count",
  "project.attachment_count",
  "project.is_ai_enhanced",
  "project.link",
  "project.cover_image",
  "project.image_order",
  "project.step_image_order",
  "project.timer_seconds",
  "yarn.name",
  "yarn.description",
  "yarn.brand",
  "yarn.colorway",
  "yarn.dye_lot",
  "yarn.fiber_content",
  "yarn.weight_category",
  "yarn.recommended_needles",
  "yarn.weight_grams",
  "yarn.length_meters",
  "yarn.notes",
  "yarn.photo_count",
  "yarn.is_ai_enhanced",
  "yarn.link",
  "yarn.primary_photo",
  "yarn.photo_order",
  "category.name",
]);

function compatibleChangeValue(
  field: string,
  type: ChangeValue["type"],
): boolean {
  if (type === "legacy_text")
    return field === "project.tags" || field === "project.yarns";
  if (field.endsWith(".link")) return type === "link_changed";
  if (field === "project.tags") return type === "text_list";
  if (field === "project.yarns") return type === "yarns";
  if (field === "project.steps") return type === "steps";
  if (
    field.endsWith("_count") ||
    field.endsWith("_grams") ||
    field.endsWith("_meters") ||
    field === "project.timer_seconds"
  )
    return type === "integer";
  if (field.endsWith(".is_ai_enhanced")) return type === "boolean";
  if (field.endsWith(".tags") || field.endsWith("_order"))
    return type === "text_list";
  return type === "text";
}

function parseChangeValue(value: unknown, field: string): ChangeValue {
  if (!record(value) || typeof value.type !== "string") throw invalidResponse();
  switch (value.type) {
    case "text":
      if (
        !(value.value === null || typeof value.value === "string") ||
        (typeof value.value === "string" && value.value.length > 16_384)
      )
        throw invalidResponse();
      return { type: "text", value: value.value };
    case "text_list":
    case "yarns":
      if (
        !Array.isArray(value.value) ||
        value.value.length > 5000 ||
        !value.value.every(
          (item) => typeof item === "string" && item.length <= 16_384,
        )
      )
        throw invalidResponse();
      return { type: value.type, value: value.value };
    case "integer":
      if (!(
        value.value === null ||
        (typeof value.value === "number" && Number.isSafeInteger(value.value))
      ))
        throw invalidResponse();
      return { type: "integer", value: value.value };
    case "boolean":
    case "link_changed":
      if (typeof value.value !== "boolean") throw invalidResponse();
      return { type: value.type, value: value.value };
    case "steps":
      if (!Array.isArray(value.value) || value.value.length > 5000)
        throw invalidResponse();
      return {
        type: "steps",
        value: value.value.map((step: unknown) => {
          if (
            !record(step) ||
            !Number.isSafeInteger(step.number) ||
            typeof step.title !== "string" ||
            step.title.length > 16_384 ||
            !(
              step.description === null ||
              (typeof step.description === "string" &&
                step.description.length <= 16_384)
            )
          )
            throw invalidResponse();
          return {
            number: step.number as number,
            title: step.title,
            description: step.description as string | null,
          };
        }),
      };
    case "legacy_text":
      if (
        typeof value.value !== "string" ||
        value.value.length > 16_384 ||
        !compatibleChangeValue(field, "legacy_text")
      )
        throw invalidResponse();
      return { type: "legacy_text", value: value.value };
    default:
      throw invalidResponse();
  }
}

export function parseChangeSet(value: unknown): ChangeSetV1 {
  if (
    !record(value) ||
    value.version !== 1 ||
    !Array.isArray(value.items) ||
    value.items.length > 256
  )
    throw invalidResponse();
  const items = value.items.map((item: unknown) => {
    if (
      !record(item) ||
      typeof item.field !== "string" ||
      item.field.length > 96 ||
      !changeFields.has(item.field)
    )
      throw invalidResponse();
    const before = parseChangeValue(item.before, item.field);
    const after = parseChangeValue(item.after, item.field);
    if (
      !compatibleChangeValue(item.field, before.type) ||
      !compatibleChangeValue(item.field, after.type)
    )
      throw invalidResponse();
    return {
      field: item.field,
      before,
      after,
    };
  });
  return { version: 1, items };
}

function parseFilterUser(value: unknown): AuditFilterUser | null {
  if (value === undefined || value === null) return null;
  if (!record(value) || !positiveInteger(value.id)) throw invalidResponse();
  if (typeof value.email !== "string") throw invalidResponse();
  return { id: value.id, email: value.email };
}

export function auditFiltersFromSearch(search: string): AuditFilters {
  const params = new URLSearchParams(search);
  const page = Number(params.get("page") ?? "1");
  const entity = params.get("entity");
  const user = Number(params.get("user") ?? "");
  return {
    page: Number.isSafeInteger(page) && page >= 1 && page <= 10000 ? page : 1,
    entity:
      entity === "project" ||
      entity === "yarn" ||
      entity === "category" ||
      entity === "server"
        ? entity
        : "all",
    action: params.get("action") ?? "",
    user: Number.isSafeInteger(user) && user > 0 ? user : null,
  };
}

export function auditSearchFromFilters(filters: AuditFilters): string {
  const params = new URLSearchParams();
  if (filters.page > 1) params.set("page", String(filters.page));
  if (filters.entity !== "all") params.set("entity", filters.entity);
  const action = filters.action.trim();
  if (action) params.set("action", action);
  if (filters.user !== null) params.set("user", String(filters.user));
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

/** Start acting as `id`; the cookie session itself switches, nothing is returned to store. */
export async function impersonateUser(
  id: number,
  currentSession: SessionData,
): Promise<void> {
  const payload: unknown = await apiRequest(
    `/api/v2/admin/users/${id}/impersonate`,
    { method: "POST", csrfToken: currentSession.csrf_token },
  );
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
}

/** End the impersonation of this browser session. */
export async function stopImpersonation(
  currentSession: SessionData,
): Promise<void> {
  const payload: unknown = await apiRequest(
    "/api/v2/admin/impersonation/stop",
    { method: "POST", csrfToken: currentSession.csrf_token },
  );
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
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
    user: parseFilterUser(record(payload.meta) ? payload.meta.user : undefined),
  };
}

export async function getAuditEventDetail(
  eventId: number,
  signal?: AbortSignal,
): Promise<AuditEventDetail> {
  if (!positiveInteger(eventId)) throw invalidResponse();
  const payload: unknown = await apiRequest(`/api/v2/admin/audit/${eventId}`, {
    signal,
  });
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
  const event = parseAuditEvent(payload.data.event);
  const changes =
    payload.data.changes === null ? null : parseChangeSet(payload.data.changes);
  const unavailableReason = payload.data.unavailable_reason;
  if (
    !(
      unavailableReason === null ||
      unavailableReason === "not_recorded" ||
      unavailableReason === "malformed" ||
      unavailableReason === "too_large"
    ) ||
    (changes !== null && unavailableReason !== null) ||
    (changes === null && unavailableReason === null && event.has_changes)
  )
    throw invalidResponse();
  return {
    event,
    changes,
    unavailable_reason: unavailableReason,
  };
}
