import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";
import { accessField, type ResourceAccess } from "#lib/access.ts";
import { ownerField, type ResourceOwner } from "#lib/owner.ts";
import type { LibraryScope } from "#lib/library-scope.svelte.ts";
import { ownerChoiceFromParam, type OwnerChoice } from "#lib/owner-filter.ts";
import {
  trashChoiceFromParam,
  trashField,
  type TrashChoice,
  type TrashFields,
} from "#lib/trash-filter.ts";

export interface YarnSummary extends TrashFields {
  id: number;
  name: string;
  brand: string | null;
  colorway: string | null;
  weight_category: string | null;
  is_favorite: boolean;
  updated_at: string;
  preview_url: string | null;
  /** Who owns the yarn; absent on responses that predate owner info. */
  owner?: ResourceOwner | null;
  /** What the signed-in user may do with it; absent on responses that predate sharing. */
  access?: ResourceAccess | null;
}

export interface YarnPhoto {
  id: number;
  url: string;
  thumbnail_url: string;
  alt_text: string | null;
  is_primary: boolean;
}

export interface YarnDetail extends YarnSummary {
  description: string | null;
  description_html: string | null;
  dye_lot: string | null;
  fiber_content: string | null;
  recommended_needles: string | null;
  weight_grams: number | null;
  length_meters: number | null;
  notes: string | null;
  notes_html: string | null;
  link: string | null;
  link_archive: string | null;
  is_ai_enhanced: boolean;
  created_at: string;
  project_ids: number[];
  photos: YarnPhoto[];
}

export interface YarnWrite {
  name: string;
  description: string | null;
  brand: string | null;
  colorway: string | null;
  dye_lot: string | null;
  fiber_content: string | null;
  weight_category: string | null;
  recommended_needles: string | null;
  weight_grams: number | null;
  length_meters: number | null;
  notes: string | null;
  link: string | null;
  is_ai_enhanced: boolean;
  /** Pictures fetched by the server and attached when the yarn is created (imports). */
  image_urls?: string[];
}

export interface YarnPage {
  data: YarnSummary[];
  meta: {
    page: number;
    per_page: number;
    has_more: boolean;
    scope?: LibraryScope;
  };
}

export interface LinkedProject {
  id: number;
  name: string;
  category: string | null;
  preview_url: string | null;
}

export interface YarnFilters {
  query: string;
  favorite: boolean;
  /** Whose yarns: empty = everybody's, `me`, `shared` or an owner id (`?owner=` of the URL). */
  owner: OwnerChoice;
  /** `only` lists the trash (`?trashed=only`); empty is the normal list. */
  trashed: TrashChoice;
  page: number;
}

const positiveInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;
const nullableString = (value: unknown): value is string | null =>
  value === null || typeof value === "string";
const nullableNonnegativeInteger = (value: unknown): value is number | null =>
  value === null || (Number.isSafeInteger(value) && Number(value) >= 0);
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

export function yarnFiltersFromSearch(search: string): YarnFilters {
  const params = new URLSearchParams(search);
  const page = Number(params.get("page") ?? "1");
  return {
    query: params.get("q")?.trim() ?? "",
    favorite: params.get("favorite") === "true",
    owner: ownerChoiceFromParam(params.get("owner")),
    trashed: trashChoiceFromParam(params.get("trashed")),
    page: Number.isSafeInteger(page) && page > 0 ? page : 1,
  };
}

export function yarnSearchFromFilters(filters: YarnFilters): string {
  const params = new URLSearchParams();
  if (filters.query) params.set("q", filters.query);
  if (filters.favorite) params.set("favorite", "true");
  if (filters.owner) params.set("owner", filters.owner);
  if (filters.trashed) params.set("trashed", filters.trashed);
  if (filters.page > 1) params.set("page", String(filters.page));
  return params.toString();
}

export interface ListScope {
  /** Whose items to list. Omitted: the server default (Everyone for admins). */
  scope?: LibraryScope;
  /** Restrict to one owner's items (administrators only). */
  ownerId?: number;
}

export function applyListScope(params: URLSearchParams, scope: ListScope) {
  if (scope.scope) params.set("scope", scope.scope);
  if (scope.ownerId !== undefined)
    params.set("owner_id", String(scope.ownerId));
}

export async function listYarns(
  search: string,
  signal: AbortSignal,
  scope: ListScope = {},
): Promise<YarnPage> {
  const params = new URLSearchParams(search);
  applyListScope(params, scope);
  const suffix = params.size ? `?${params}` : "";
  const payload: unknown = await apiRequest(`/api/v2/yarns${suffix}`, {
    signal,
  });
  if (!record(payload) || !Array.isArray(payload.data) || !record(payload.meta))
    throw invalidResponse();
  const { page, per_page, has_more } = payload.meta;
  if (
    !positiveInteger(page) ||
    !positiveInteger(per_page) ||
    typeof has_more !== "boolean"
  )
    throw invalidResponse();
  return {
    data: payload.data.map(parseYarnSummary),
    meta: {
      page,
      per_page,
      has_more,
      ...(payload.meta.scope === "mine" || payload.meta.scope === "all"
        ? { scope: payload.meta.scope }
        : {}),
    },
  };
}

export async function getYarn(
  id: number,
  signal?: AbortSignal,
): Promise<YarnDetail> {
  const payload: unknown = await apiRequest(`/api/v2/yarns/${id}`, { signal });
  if (!record(payload)) throw invalidResponse();
  return parseYarnDetail(payload.data);
}

export async function getLinkedProject(
  id: number,
  signal?: AbortSignal,
): Promise<LinkedProject> {
  const payload: unknown = await apiRequest(`/api/v2/projects/${id}`, {
    signal,
  });
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
  const project = payload.data;
  const projectId = project.id;
  if (
    !positiveInteger(projectId) ||
    projectId !== id ||
    typeof project.name !== "string" ||
    !nullableString(project.category) ||
    !nullableString(project.preview_url) ||
    (project.preview_url !== null &&
      !ownerProjectThumbnailUrl(project.preview_url, projectId)) ||
    !Array.isArray(project.images)
  )
    throw invalidResponse();
  project.images.forEach((value: unknown) => {
    if (
      !record(value) ||
      !positiveInteger(value.id) ||
      typeof value.is_title_image !== "boolean" ||
      !ownerProjectThumbnailUrl(value.thumbnail_url, projectId)
    )
      throw invalidResponse();
  });
  return {
    id: projectId,
    name: project.name,
    category: project.category,
    preview_url: project.preview_url,
  };
}

function ownerProjectThumbnailUrl(
  value: unknown,
  projectId: number,
): value is string {
  if (typeof value !== "string") return false;
  const prefix = `/media/thumbnails/projects/${projectId}/`;
  if (!value.startsWith(prefix)) return false;
  const filename = value.slice(prefix.length);
  return (
    filename.length > 0 &&
    /^[A-Za-z0-9._-]+$/.test(filename) &&
    !filename.includes("..")
  );
}

export async function createYarn(
  value: YarnWrite,
  session: SessionData,
): Promise<YarnDetail> {
  const payload: unknown = await apiRequest("/api/v2/yarns", {
    method: "POST",
    csrfToken: session.csrf_token,
    body: value,
  });
  if (!record(payload)) throw invalidResponse();
  return parseYarnDetail(payload.data);
}

export async function updateYarn(
  id: number,
  value: YarnWrite,
  expectedUpdatedAt: string,
  session: SessionData,
): Promise<YarnDetail> {
  const payload: unknown = await apiRequest(`/api/v2/yarns/${id}`, {
    method: "PUT",
    csrfToken: session.csrf_token,
    body: { ...value, expected_updated_at: expectedUpdatedAt },
  });
  if (!record(payload)) throw invalidResponse();
  return parseYarnDetail(payload.data);
}

export async function deleteYarn(
  id: number,
  session: SessionData,
): Promise<void> {
  await apiRequest(`/api/v2/yarns/${id}`, {
    method: "DELETE",
    csrfToken: session.csrf_token,
  });
}

export async function setYarnFavorite(
  id: number,
  favorite: boolean,
  session: SessionData,
): Promise<YarnDetail> {
  const payload: unknown = await apiRequest(`/api/v2/yarns/${id}/favorite`, {
    method: favorite ? "PUT" : "DELETE",
    csrfToken: session.csrf_token,
  });
  if (!record(payload)) throw invalidResponse();
  return parseYarnDetail(payload.data);
}

export async function uploadYarnPhoto(
  id: number,
  file: File,
  session: SessionData,
): Promise<YarnPhoto> {
  if (file.size > 25 * 1024 * 1024) {
    throw new ApiError(
      t("This photo is larger than the 25 MiB limit."),
      413,
      "payload_too_large",
    );
  }
  const body = new FormData();
  body.append("file", file);
  const payload: unknown = await apiRequest(`/api/v2/yarns/${id}/photos`, {
    method: "POST",
    csrfToken: session.csrf_token,
    body,
  });
  if (!record(payload)) throw invalidResponse();
  return parseYarnPhoto(payload.data);
}

export async function deleteYarnPhoto(
  yarnId: number,
  photoId: number,
  session: SessionData,
): Promise<void> {
  await apiRequest(`/api/v2/yarns/${yarnId}/photos/${photoId}`, {
    method: "DELETE",
    csrfToken: session.csrf_token,
  });
}

export function parseYarnSummary(value: unknown): YarnSummary {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    typeof value.name !== "string" ||
    !nullableString(value.brand) ||
    !nullableString(value.colorway) ||
    !nullableString(value.weight_category) ||
    typeof value.is_favorite !== "boolean" ||
    typeof value.updated_at !== "string" ||
    !nullableString(value.preview_url)
  )
    throw invalidResponse();
  return {
    id: value.id,
    name: value.name,
    brand: value.brand,
    colorway: value.colorway,
    weight_category: value.weight_category,
    is_favorite: value.is_favorite,
    updated_at: value.updated_at,
    preview_url: value.preview_url,
    ...ownerField(value.owner),
    ...accessField(value.access),
    ...trashField(value),
  };
}

export function parseYarnPhoto(value: unknown): YarnPhoto {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    typeof value.url !== "string" ||
    typeof value.thumbnail_url !== "string" ||
    !nullableString(value.alt_text) ||
    typeof value.is_primary !== "boolean"
  )
    throw invalidResponse();
  return {
    id: value.id,
    url: value.url,
    thumbnail_url: value.thumbnail_url,
    alt_text: value.alt_text,
    is_primary: value.is_primary,
  };
}

export function parseYarnDetail(value: unknown): YarnDetail {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    typeof value.name !== "string" ||
    !nullableString(value.brand) ||
    !nullableString(value.colorway) ||
    !nullableString(value.weight_category) ||
    typeof value.is_favorite !== "boolean" ||
    typeof value.updated_at !== "string" ||
    !nullableString(value.description) ||
    !nullableString(value.description_html) ||
    !nullableString(value.dye_lot) ||
    !nullableString(value.fiber_content) ||
    !nullableString(value.recommended_needles) ||
    !nullableNonnegativeInteger(value.weight_grams) ||
    !nullableNonnegativeInteger(value.length_meters) ||
    !nullableString(value.notes) ||
    !nullableString(value.notes_html) ||
    !nullableString(value.link) ||
    !nullableString(value.link_archive) ||
    typeof value.is_ai_enhanced !== "boolean" ||
    typeof value.created_at !== "string" ||
    !Array.isArray(value.project_ids) ||
    !value.project_ids.every(positiveInteger) ||
    !Array.isArray(value.photos)
  )
    throw invalidResponse();
  const photos = value.photos.map(parseYarnPhoto);
  return {
    id: value.id,
    name: value.name,
    brand: value.brand,
    colorway: value.colorway,
    weight_category: value.weight_category,
    is_favorite: value.is_favorite,
    updated_at: value.updated_at,
    preview_url:
      photos.find((photo) => photo.is_primary)?.thumbnail_url ??
      photos[0]?.thumbnail_url ??
      null,
    ...ownerField(value.owner),
    ...accessField(value.access),
    ...trashField(value),
    description: value.description,
    description_html: value.description_html,
    dye_lot: value.dye_lot,
    fiber_content: value.fiber_content,
    recommended_needles: value.recommended_needles,
    weight_grams: value.weight_grams,
    length_meters: value.length_meters,
    notes: value.notes,
    notes_html: value.notes_html,
    link: value.link,
    link_archive: value.link_archive,
    is_ai_enhanced: value.is_ai_enhanced,
    created_at: value.created_at,
    project_ids: value.project_ids,
    photos,
  };
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    200,
    "invalid_response",
  );
}
