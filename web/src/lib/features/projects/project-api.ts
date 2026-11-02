import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export interface ProjectSummary {
  id: number;
  name: string;
  category: string | null;
  tags: string[];
  is_favorite: boolean;
  updated_at: string;
  preview_url: string | null;
}

export interface ProjectStep {
  id: number;
  title: string;
  description: string | null;
  description_html: string | null;
  step_number: number;
}

export interface ProjectImage {
  id: number;
  url: string;
  thumbnail_url: string;
  alt_text: string | null;
  image_type: string;
  is_title_image: boolean;
  is_stitch_sample: boolean;
  step_id: number | null;
}

export interface ProjectAttachment {
  id: number;
  filename: string;
  original_filename: string | null;
  content_type: string;
  size_bytes: number;
  url: string;
}

export interface ProjectYarn {
  id: number;
  name: string;
  brand: string | null;
  colorway: string | null;
  fiber_content: string | null;
  weight_category: string | null;
  recommended_needles: string | null;
  weight_grams: number | null;
  length_meters: number | null;
  notes_html: string | null;
  link: string | null;
  preview_url: string | null;
}

export interface ProjectCategory {
  id: number;
  name: string;
}

export interface ProjectDetail extends ProjectSummary {
  yarn: string | null;
  needles: string | null;
  stitch_sample: string | null;
  description: string | null;
  description_html: string | null;
  notes: string | null;
  notes_html: string | null;
  other_materials: string | null;
  other_materials_html: string | null;
  link: string | null;
  link_archive: string | null;
  is_ai_enhanced: boolean;
  created_at: string;
  yarn_ids: number[];
  steps: ProjectStep[];
  images: ProjectImage[];
  attachments: ProjectAttachment[];
}

export interface ProjectStepWrite {
  id?: number;
  title: string;
  description: string | null;
  step_number: number;
  /** Optional create-time URL suggestions used by the external import flow. */
  image_urls?: string[];
}

export interface ProjectWrite {
  name: string;
  category: string | null;
  needles: string | null;
  stitch_sample: string | null;
  description: string | null;
  notes: string | null;
  other_materials: string | null;
  tags: string[];
  link: string | null;
  is_ai_enhanced: boolean;
  yarn_ids: number[];
  steps: ProjectStepWrite[];
  /** Optional project image URLs used only during project creation/import. */
  image_urls?: string[];
}

export interface ProjectFilters {
  query: string;
  category: string;
  tag: string;
  favorite: boolean;
  page: number;
}

export interface ProjectPage {
  data: ProjectSummary[];
  meta: { page: number; per_page: number; has_more: boolean };
}

const emptyFilters: ProjectFilters = {
  query: "",
  category: "",
  tag: "",
  favorite: false,
  page: 1,
};

export function filtersFromSearch(search: string): ProjectFilters {
  const params = new URLSearchParams(search);
  const requestedPage = Number(params.get("page") ?? "1");
  return {
    query: params.get("q")?.trim() ?? "",
    category: params.get("category")?.trim() ?? "",
    tag: params.get("tag")?.trim() ?? "",
    favorite: params.get("favorite") === "true",
    page:
      Number.isSafeInteger(requestedPage) && requestedPage > 0
        ? requestedPage
        : emptyFilters.page,
  };
}

export function searchFromFilters(filters: ProjectFilters): string {
  const params = new URLSearchParams();
  if (filters.query) params.set("q", filters.query);
  if (filters.category) params.set("category", filters.category);
  if (filters.tag) params.set("tag", filters.tag);
  if (filters.favorite) params.set("favorite", "true");
  if (filters.page > 1) params.set("page", String(filters.page));
  return params.toString();
}

export async function listProjects(
  search: string,
  signal: AbortSignal,
): Promise<ProjectPage> {
  const query = new URLSearchParams(search);
  const suffix = query.size ? `?${query}` : "";
  const payload: unknown = await apiRequest(`/api/v2/projects${suffix}`, {
    signal,
  });
  if (
    !isRecord(payload) ||
    !Array.isArray(payload.data) ||
    !isRecord(payload.meta)
  )
    throw invalidResponse();
  const { page, per_page, has_more } = payload.meta;
  if (
    !isSafePositiveInteger(page) ||
    !isSafePositiveInteger(per_page) ||
    typeof has_more !== "boolean"
  )
    throw invalidResponse();
  const data = payload.data.map(parseProjectSummary);
  return { data, meta: { page, per_page, has_more } };
}

export async function listCategories(
  signal: AbortSignal,
): Promise<ProjectCategory[]> {
  const payload: unknown = await apiRequest("/api/v2/categories", { signal });
  if (!isRecord(payload) || !Array.isArray(payload.data))
    throw invalidResponse();
  return payload.data.map((value: unknown) => {
    if (
      !isRecord(value) ||
      !isSafePositiveInteger(value.id) ||
      typeof value.name !== "string"
    )
      throw invalidResponse();
    return { id: value.id, name: value.name };
  });
}

export async function getProject(
  id: number,
  signal: AbortSignal,
): Promise<ProjectDetail> {
  const payload: unknown = await apiRequest(`/api/v2/projects/${id}`, {
    signal,
  });
  if (!isRecord(payload)) throw invalidResponse();
  return parseProjectDetail(payload.data);
}

export function parseProjectDetail(value: unknown): ProjectDetail {
  return parseProjectDetailValue(value);
}

export async function createProject(
  value: ProjectWrite,
  session: SessionData,
  signal?: AbortSignal,
): Promise<ProjectDetail> {
  const payload: unknown = await apiRequest("/api/v2/projects", {
    method: "POST",
    csrfToken: session.csrf_token,
    body: value,
    signal,
  });
  if (!isRecord(payload)) throw invalidResponse();
  return parseProjectDetailValue(payload.data);
}

export async function updateProject(
  id: number,
  value: ProjectWrite,
  expectedUpdatedAt: string,
  session: SessionData,
  signal?: AbortSignal,
): Promise<ProjectDetail> {
  const payload: unknown = await apiRequest(`/api/v2/projects/${id}`, {
    method: "PUT",
    csrfToken: session.csrf_token,
    body: { ...value, expected_updated_at: expectedUpdatedAt },
    signal,
  });
  if (!isRecord(payload)) throw invalidResponse();
  return parseProjectDetailValue(payload.data);
}

export async function deleteProject(
  id: number,
  session: SessionData,
  signal?: AbortSignal,
): Promise<void> {
  await apiRequest(`/api/v2/projects/${id}`, {
    method: "DELETE",
    csrfToken: session.csrf_token,
    signal,
  });
}

export async function uploadProjectImage(
  projectId: number,
  file: File,
  altText: string,
  session: SessionData,
  stepId?: number,
  signal?: AbortSignal,
): Promise<ProjectImage> {
  assertProjectMediaSize(file);
  const body = new FormData();
  body.append("file", file);
  if (altText.trim()) body.append("alt_text", altText.trim());
  const path = stepId
    ? `/api/v2/projects/${projectId}/steps/${stepId}/images`
    : `/api/v2/projects/${projectId}/images/title`;
  const payload: unknown = await apiRequest(path, {
    method: "POST",
    csrfToken: session.csrf_token,
    body,
    signal,
  });
  if (!isRecord(payload)) throw invalidResponse();
  return parseImage(payload.data);
}

export async function uploadProjectAttachment(
  projectId: number,
  file: File,
  session: SessionData,
  signal?: AbortSignal,
): Promise<ProjectAttachment> {
  assertProjectMediaSize(file);
  const body = new FormData();
  body.append("file", file);
  const payload: unknown = await apiRequest(
    `/api/v2/projects/${projectId}/attachments`,
    {
      method: "POST",
      csrfToken: session.csrf_token,
      body,
      signal,
    },
  );
  if (!isRecord(payload)) throw invalidResponse();
  return parseAttachment(payload.data);
}

export async function deleteProjectImage(
  projectId: number,
  imageId: number,
  session: SessionData,
  signal?: AbortSignal,
): Promise<void> {
  await apiRequest(`/api/v2/projects/${projectId}/images/${imageId}`, {
    method: "DELETE",
    csrfToken: session.csrf_token,
    signal,
  });
}

export async function deleteProjectAttachment(
  projectId: number,
  attachmentId: number,
  session: SessionData,
  signal?: AbortSignal,
): Promise<void> {
  await apiRequest(
    `/api/v2/projects/${projectId}/attachments/${attachmentId}`,
    {
      method: "DELETE",
      csrfToken: session.csrf_token,
      signal,
    },
  );
}

function assertProjectMediaSize(file: File): void {
  if (file.size > 25 * 1024 * 1024) {
    throw new ApiError(
      t("This upload is larger than the 25 MiB limit."),
      413,
      "payload_too_large",
    );
  }
}

export async function getYarn(
  id: number,
  signal: AbortSignal,
): Promise<ProjectYarn> {
  const payload: unknown = await apiRequest(`/api/v2/yarns/${id}`, { signal });
  if (!isRecord(payload) || !isRecord(payload.data)) throw invalidResponse();
  const yarn = payload.data;
  if (
    !isSafePositiveInteger(yarn.id) ||
    yarn.id !== id ||
    typeof yarn.name !== "string" ||
    !nullableString(yarn.brand) ||
    !nullableString(yarn.colorway) ||
    !nullableString(yarn.fiber_content) ||
    !nullableString(yarn.weight_category) ||
    !nullableString(yarn.recommended_needles) ||
    !nullableString(yarn.notes_html) ||
    !nullableString(yarn.link) ||
    !nullableNumber(yarn.weight_grams) ||
    !nullableNumber(yarn.length_meters) ||
    !Array.isArray(yarn.photos)
  )
    throw invalidResponse();
  const photos = yarn.photos.map((value: unknown) => {
    if (
      !isRecord(value) ||
      !isSafePositiveInteger(value.id) ||
      typeof value.is_primary !== "boolean" ||
      !ownerThumbnailUrl(value.thumbnail_url, "yarns", id)
    )
      throw invalidResponse();
    return {
      is_primary: value.is_primary,
      thumbnail_url: value.thumbnail_url,
    };
  });
  return {
    id: yarn.id,
    name: yarn.name,
    brand: yarn.brand,
    colorway: yarn.colorway,
    fiber_content: yarn.fiber_content,
    weight_category: yarn.weight_category,
    recommended_needles: yarn.recommended_needles,
    weight_grams: yarn.weight_grams,
    length_meters: yarn.length_meters,
    notes_html: yarn.notes_html,
    link: yarn.link,
    preview_url:
      photos.find((photo) => photo.is_primary)?.thumbnail_url ??
      photos[0]?.thumbnail_url ??
      null,
  };
}

function ownerThumbnailUrl(
  value: unknown,
  kind: "projects" | "yarns",
  parentId: number,
): value is string {
  if (typeof value !== "string") return false;
  const prefix = `/media/thumbnails/${kind}/${parentId}/`;
  if (!value.startsWith(prefix)) return false;
  const filename = value.slice(prefix.length);
  return (
    filename.length > 0 &&
    /^[A-Za-z0-9._-]+$/.test(filename) &&
    !filename.includes("..")
  );
}

export async function setProjectFavorite(
  id: number,
  favorite: boolean,
  session: SessionData,
): Promise<void> {
  await apiRequest(`/api/v2/projects/${id}/favorite`, {
    method: favorite ? "PUT" : "DELETE",
    csrfToken: session.csrf_token,
  });
}

function parseProjectSummary(value: unknown): ProjectSummary {
  if (
    !isRecord(value) ||
    !isSafePositiveInteger(value.id) ||
    typeof value.name !== "string" ||
    !nullableString(value.category) ||
    !isStringArray(value.tags) ||
    typeof value.is_favorite !== "boolean" ||
    typeof value.updated_at !== "string" ||
    !nullableString(value.preview_url)
  )
    throw invalidResponse();
  return {
    id: value.id,
    name: value.name,
    category: value.category,
    tags: value.tags,
    is_favorite: value.is_favorite,
    updated_at: value.updated_at,
    preview_url: value.preview_url,
  };
}

function parseProjectDetailValue(value: unknown): ProjectDetail {
  if (
    !isRecord(value) ||
    !isSafePositiveInteger(value.id) ||
    typeof value.name !== "string" ||
    !nullableString(value.category) ||
    !isStringArray(value.tags) ||
    typeof value.is_favorite !== "boolean" ||
    typeof value.updated_at !== "string" ||
    !nullableString(value.yarn) ||
    !nullableString(value.needles) ||
    !nullableString(value.stitch_sample) ||
    !nullableString(value.description) ||
    !nullableString(value.description_html) ||
    !nullableString(value.notes) ||
    !nullableString(value.notes_html) ||
    !nullableString(value.other_materials) ||
    !nullableString(value.other_materials_html) ||
    !nullableString(value.link) ||
    !nullableString(value.link_archive) ||
    typeof value.is_ai_enhanced !== "boolean" ||
    typeof value.created_at !== "string" ||
    !isNumberArray(value.yarn_ids) ||
    !Array.isArray(value.steps) ||
    !Array.isArray(value.images) ||
    !Array.isArray(value.attachments)
  )
    throw invalidResponse();
  const steps = value.steps.map(parseStep);
  const images = value.images.map(parseImage);
  const attachments = value.attachments.map(parseAttachment);
  const preview =
    images.find((image) => image.is_title_image)?.thumbnail_url ??
    images[0]?.thumbnail_url ??
    null;
  return {
    id: value.id,
    name: value.name,
    category: value.category,
    tags: value.tags,
    is_favorite: value.is_favorite,
    updated_at: value.updated_at,
    preview_url: preview,
    yarn: value.yarn,
    needles: value.needles,
    stitch_sample: value.stitch_sample,
    description: value.description,
    description_html: value.description_html,
    notes: value.notes,
    notes_html: value.notes_html,
    other_materials: value.other_materials,
    other_materials_html: value.other_materials_html,
    link: value.link,
    link_archive: value.link_archive,
    is_ai_enhanced: value.is_ai_enhanced,
    created_at: value.created_at,
    yarn_ids: value.yarn_ids,
    steps,
    images,
    attachments,
  };
}

function parseStep(value: unknown): ProjectStep {
  if (
    !isRecord(value) ||
    !isSafePositiveInteger(value.id) ||
    typeof value.title !== "string" ||
    !nullableString(value.description) ||
    !nullableString(value.description_html) ||
    !isSafePositiveInteger(value.step_number)
  )
    throw invalidResponse();
  return {
    id: value.id,
    title: value.title,
    description: value.description,
    description_html: value.description_html,
    step_number: value.step_number,
  };
}

function parseImage(value: unknown): ProjectImage {
  if (
    !isRecord(value) ||
    !isSafePositiveInteger(value.id) ||
    typeof value.url !== "string" ||
    typeof value.thumbnail_url !== "string" ||
    !nullableString(value.alt_text) ||
    typeof value.image_type !== "string" ||
    typeof value.is_title_image !== "boolean" ||
    typeof value.is_stitch_sample !== "boolean" ||
    !(value.step_id === null || isSafePositiveInteger(value.step_id))
  )
    throw invalidResponse();
  return {
    id: value.id,
    url: value.url,
    thumbnail_url: value.thumbnail_url,
    alt_text: value.alt_text,
    image_type: value.image_type,
    is_title_image: value.is_title_image,
    is_stitch_sample: value.is_stitch_sample,
    step_id: value.step_id,
  };
}

function parseAttachment(value: unknown): ProjectAttachment {
  if (
    !isRecord(value) ||
    !isSafePositiveInteger(value.id) ||
    typeof value.filename !== "string" ||
    !nullableString(value.original_filename) ||
    typeof value.content_type !== "string" ||
    typeof value.size_bytes !== "number" ||
    !Number.isSafeInteger(value.size_bytes) ||
    typeof value.url !== "string"
  )
    throw invalidResponse();
  return {
    id: value.id,
    filename: value.filename,
    original_filename: value.original_filename,
    content_type: value.content_type,
    size_bytes: value.size_bytes,
    url: value.url,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isSafePositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && typeof value === "number" && value > 0;
}

function isNumberArray(value: unknown): value is number[] {
  return Array.isArray(value) && value.every(isSafePositiveInteger);
}

function isStringArray(value: unknown): value is string[] {
  return (
    Array.isArray(value) && value.every((item) => typeof item === "string")
  );
}

function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function nullableNumber(value: unknown): value is number | null {
  return (
    value === null || (typeof value === "number" && Number.isFinite(value))
  );
}

function invalidResponse(): ApiError {
  return new ApiError(
    t("The server returned an unexpected response."),
    500,
    "invalid_response",
  );
}
