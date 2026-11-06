import { ApiError, apiRequest } from "#lib/api.ts";

export type AdminResourceKind = "project" | "yarn" | "category";

export interface AdminResourceOwner {
  id: number;
  email: string;
  avatar_url: string | null;
}

export interface AdminResourceProject {
  id: number;
  name: string;
  category: string | null;
  yarn: string | null;
  needles: string | null;
  stitch_sample: string | null;
  description: string | null;
  description_html: string | null;
  notes: string | null;
  notes_html: string | null;
  other_materials: string | null;
  other_materials_html: string | null;
  tags: string[];
  link: string | null;
  link_archive: string | null;
  is_ai_enhanced: boolean;
  is_favorite: boolean;
  created_at: string;
  updated_at: string;
  yarn_ids: number[];
  steps: AdminProjectStep[];
  images: AdminProjectImage[];
  attachments: AdminProjectAttachment[];
}

export interface AdminProjectStep {
  id: number;
  title: string;
  description: string | null;
  description_html: string | null;
  step_number: number;
}

export interface AdminProjectImage {
  id: number;
  url: string;
  thumbnail_url: string;
  alt_text: string;
  image_type: string;
  is_title_image: boolean;
  is_stitch_sample: boolean;
  step_id: number | null;
}

export interface AdminProjectAttachment {
  id: number;
  filename: string;
  original_filename: string;
  content_type: string;
  size_bytes: number;
  url: string;
}

export interface AdminResourceYarn {
  id: number;
  name: string;
  description: string | null;
  description_html: string | null;
  brand: string | null;
  colorway: string | null;
  dye_lot: string | null;
  fiber_content: string | null;
  weight_category: string | null;
  recommended_needles: string | null;
  weight_grams: number | null;
  length_meters: number | null;
  notes: string | null;
  notes_html: string | null;
  link: string | null;
  link_archive: string | null;
  is_ai_enhanced: boolean;
  is_favorite: boolean;
  created_at: string;
  updated_at: string;
  project_ids: number[];
  photos: AdminYarnPhoto[];
}

export interface AdminYarnPhoto {
  id: number;
  url: string;
  thumbnail_url: string;
  alt_text: string;
  is_primary: boolean;
}

export interface AdminResourceCategory {
  id: number;
  name: string;
}

interface AdminResourceBase<TKind extends AdminResourceKind, TResource> {
  kind: TKind;
  id: number;
  name: string;
  owner: AdminResourceOwner | null;
  resource: TResource;
  read_only: true;
}

export type AdminResourceData =
  | AdminResourceBase<"project", AdminResourceProject>
  | AdminResourceBase<"yarn", AdminResourceYarn>
  | AdminResourceBase<"category", AdminResourceCategory>;

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const positiveInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;

const nullableString = (value: unknown): value is string | null =>
  value === null || typeof value === "string";

const stringArray = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every((item) => typeof item === "string");

const integerArray = (value: unknown): value is number[] =>
  Array.isArray(value) && value.every(positiveInteger);

function invalidResponse(): ApiError {
  return new ApiError(
    "The server returned an unexpected response.",
    200,
    "invalid_response",
  );
}

function adminMediaUrl(
  value: unknown,
  kind: AdminResourceKind,
  resourceId: number,
  mediaKind: "image" | "attachment" | "photo",
  thumbnail = false,
): value is string {
  if (typeof value !== "string") return false;
  const suffix = thumbnail ? "/thumbnail" : "";
  return (
    value ===
    `/api/v2/admin/resources/${kind}/${resourceId}/media/${mediaKind}/${extractMediaId(value)}${suffix}`
  );
}

function extractMediaId(value: string): number | null {
  const match =
    /^\/api\/v2\/admin\/resources\/(?:project|yarn)\/\d+\/media\/(?:image|attachment|photo)\/(\d+)(?:\/thumbnail)?$/.exec(
      value,
    );
  if (!match) return null;
  const id = Number(match[1]);
  return positiveInteger(id) ? id : null;
}

function parseOwner(value: unknown): AdminResourceOwner | null {
  if (value === null) return null;
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    typeof value.email !== "string" ||
    !validOwnerAvatar(value.avatar_url, value.id)
  )
    throw invalidResponse();
  return { id: value.id, email: value.email, avatar_url: value.avatar_url };
}

function validOwnerAvatar(value: unknown, id: number): value is string | null {
  if (value === null) return true;
  if (typeof value !== "string") return false;
  const match =
    /^\/api\/v2\/admin\/users\/(\d+)\/avatar\/thumb_[A-Za-z0-9._-]+\.jpg$/.exec(
      value,
    );
  return match !== null && Number(match[1]) === id;
}

function parseStep(value: unknown): AdminProjectStep {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    typeof value.title !== "string" ||
    !nullableString(value.description) ||
    !nullableString(value.description_html) ||
    !Number.isSafeInteger(value.step_number)
  )
    throw invalidResponse();
  return {
    id: value.id,
    title: value.title,
    description: value.description,
    description_html: value.description_html,
    step_number: value.step_number as number,
  };
}

function parseProjectImage(
  value: unknown,
  projectId: number,
): AdminProjectImage {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    !adminMediaUrl(value.url, "project", projectId, "image") ||
    !adminMediaUrl(value.thumbnail_url, "project", projectId, "image", true) ||
    typeof value.alt_text !== "string" ||
    typeof value.image_type !== "string" ||
    typeof value.is_title_image !== "boolean" ||
    typeof value.is_stitch_sample !== "boolean" ||
    !(value.step_id === null || positiveInteger(value.step_id))
  )
    throw invalidResponse();
  return {
    id: value.id,
    url: value.url,
    thumbnail_url: value.thumbnail_url,
    alt_text: value.alt_text,
    image_type: value.image_type,
    // The cover is the server's canonical one (`is_primary`, which also picks
    // `preview_url`). `is_title_image` can be a stale flag from an import, so
    // it is only the fallback for payloads without `is_primary`.
    is_title_image:
      typeof value.is_primary === "boolean"
        ? value.is_primary
        : value.is_title_image,
    is_stitch_sample: value.is_stitch_sample,
    step_id: value.step_id,
  };
}

function parseAttachment(
  value: unknown,
  projectId: number,
): AdminProjectAttachment {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    typeof value.filename !== "string" ||
    typeof value.original_filename !== "string" ||
    typeof value.content_type !== "string" ||
    !Number.isSafeInteger(value.size_bytes) ||
    Number(value.size_bytes) < 0 ||
    !adminMediaUrl(value.url, "project", projectId, "attachment")
  )
    throw invalidResponse();
  return {
    id: value.id,
    filename: value.filename,
    original_filename: value.original_filename,
    content_type: value.content_type,
    size_bytes: value.size_bytes as number,
    url: value.url,
  };
}

function parseProject(value: unknown, id: number): AdminResourceProject {
  if (
    !record(value) ||
    value.id !== id ||
    typeof value.name !== "string" ||
    !nullableString(value.category) ||
    !nullableString(value.yarn) ||
    !nullableString(value.needles) ||
    !nullableString(value.stitch_sample) ||
    !nullableString(value.description) ||
    !nullableString(value.description_html) ||
    !nullableString(value.notes) ||
    !nullableString(value.notes_html) ||
    !nullableString(value.other_materials) ||
    !nullableString(value.other_materials_html) ||
    !stringArray(value.tags) ||
    !nullableString(value.link) ||
    !nullableString(value.link_archive) ||
    typeof value.is_ai_enhanced !== "boolean" ||
    typeof value.is_favorite !== "boolean" ||
    typeof value.created_at !== "string" ||
    typeof value.updated_at !== "string" ||
    !integerArray(value.yarn_ids) ||
    !Array.isArray(value.steps) ||
    !Array.isArray(value.images) ||
    !Array.isArray(value.attachments)
  )
    throw invalidResponse();
  return {
    id,
    name: value.name,
    category: value.category,
    yarn: value.yarn,
    needles: value.needles,
    stitch_sample: value.stitch_sample,
    description: value.description,
    description_html: value.description_html,
    notes: value.notes,
    notes_html: value.notes_html,
    other_materials: value.other_materials,
    other_materials_html: value.other_materials_html,
    tags: value.tags,
    link: value.link,
    link_archive: value.link_archive,
    is_ai_enhanced: value.is_ai_enhanced,
    is_favorite: value.is_favorite,
    created_at: value.created_at,
    updated_at: value.updated_at,
    yarn_ids: value.yarn_ids,
    steps: value.steps.map(parseStep),
    images: value.images.map((image) => parseProjectImage(image, id)),
    attachments: value.attachments.map((attachment) =>
      parseAttachment(attachment, id),
    ),
  };
}

function parseYarnPhoto(value: unknown, yarnId: number): AdminYarnPhoto {
  if (
    !record(value) ||
    !positiveInteger(value.id) ||
    !adminMediaUrl(value.url, "yarn", yarnId, "photo") ||
    !adminMediaUrl(value.thumbnail_url, "yarn", yarnId, "photo", true) ||
    typeof value.alt_text !== "string" ||
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

function parseYarn(value: unknown, id: number): AdminResourceYarn {
  if (
    !record(value) ||
    value.id !== id ||
    typeof value.name !== "string" ||
    !nullableString(value.description) ||
    !nullableString(value.description_html) ||
    !nullableString(value.brand) ||
    !nullableString(value.colorway) ||
    !nullableString(value.dye_lot) ||
    !nullableString(value.fiber_content) ||
    !nullableString(value.weight_category) ||
    !nullableString(value.recommended_needles) ||
    !(
      value.weight_grams === null || Number.isSafeInteger(value.weight_grams)
    ) ||
    !(
      value.length_meters === null || Number.isSafeInteger(value.length_meters)
    ) ||
    !nullableString(value.notes) ||
    !nullableString(value.notes_html) ||
    !nullableString(value.link) ||
    !nullableString(value.link_archive) ||
    typeof value.is_ai_enhanced !== "boolean" ||
    typeof value.is_favorite !== "boolean" ||
    typeof value.created_at !== "string" ||
    typeof value.updated_at !== "string" ||
    !integerArray(value.project_ids) ||
    !Array.isArray(value.photos)
  )
    throw invalidResponse();
  return {
    id,
    name: value.name,
    description: value.description,
    description_html: value.description_html,
    brand: value.brand,
    colorway: value.colorway,
    dye_lot: value.dye_lot,
    fiber_content: value.fiber_content,
    weight_category: value.weight_category,
    recommended_needles: value.recommended_needles,
    weight_grams: value.weight_grams as number | null,
    length_meters: value.length_meters as number | null,
    notes: value.notes,
    notes_html: value.notes_html,
    link: value.link,
    link_archive: value.link_archive,
    is_ai_enhanced: value.is_ai_enhanced,
    is_favorite: value.is_favorite,
    created_at: value.created_at,
    updated_at: value.updated_at,
    project_ids: value.project_ids,
    photos: value.photos.map((photo) => parseYarnPhoto(photo, id)),
  };
}

function parseResource(value: unknown): AdminResourceData {
  if (
    !record(value) ||
    (value.kind !== "project" &&
      value.kind !== "yarn" &&
      value.kind !== "category") ||
    !positiveInteger(value.id) ||
    typeof value.name !== "string" ||
    !(value.read_only === true)
  )
    throw invalidResponse();
  const owner = parseOwner(value.owner);
  if (value.kind === "project") {
    return {
      kind: value.kind,
      id: value.id,
      name: value.name,
      owner,
      resource: parseProject(value.resource, value.id),
      read_only: true,
    };
  }
  if (value.kind === "yarn") {
    return {
      kind: value.kind,
      id: value.id,
      name: value.name,
      owner,
      resource: parseYarn(value.resource, value.id),
      read_only: true,
    };
  }
  if (
    !record(value.resource) ||
    value.resource.id !== value.id ||
    typeof value.resource.name !== "string"
  )
    throw invalidResponse();
  return {
    kind: value.kind,
    id: value.id,
    name: value.name,
    owner,
    resource: { id: value.id, name: value.resource.name },
    read_only: true,
  };
}

export async function getAdminResource(
  kind: AdminResourceKind,
  id: number,
  signal?: AbortSignal,
): Promise<AdminResourceData> {
  if (!positiveInteger(id)) throw invalidResponse();
  const payload: unknown = await apiRequest(
    `/api/v2/admin/resources/${kind}/${id}`,
    { signal },
  );
  if (!record(payload)) throw invalidResponse();
  const resource = parseResource(payload.data);
  if (resource.kind !== kind || resource.id !== id) throw invalidResponse();
  return resource;
}
