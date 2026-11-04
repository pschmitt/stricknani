import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export type ImportedDocumentKind =
  "html" | "plain_text" | "markdown" | "pdf" | "image";

export interface ImportedYarnDraft {
  name: string | null;
  brand: string | null;
  colorway: string | null;
  weight: string | null;
  length: string | null;
  weightCategory: string | null;
  fiberContent: string | null;
  link: string | null;
  imageUrl: string | null;
  /** Quantity the pattern asks for, per size when it says so. */
  amount?: string | null;
  /** Remarks moved out of the name, such as the yarn group. */
  notes?: string | null;
}

export interface ImportedStepDraft {
  stepNumber: number;
  title: string | null;
  description: string | null;
  images: string[];
}

export type ImportedPageKind = "yarn" | "pattern" | "unknown";
export type ImportedFieldSource = "text" | "ai" | "structured" | "site";

/** A yarn product page, as extracted by the server. */
export interface ImportedYarnDetails {
  name: string | null;
  brand: string | null;
  colorway: string | null;
  description: string | null;
  fiberContent: string | null;
  weightCategory: string | null;
  weightGrams: number | null;
  lengthMeters: number | null;
  recommendedNeedles: string | null;
  gauge: string | null;
  care: string | null;
  price: string | null;
  link: string | null;
  imageUrls: string[];
}

export interface ImportedProjectDraft {
  designer: string | null;
  difficulty: string | null;
  sizes: string | null;
  tags: string[];
  name: string | null;
  description: string | null;
  category: string | null;
  yarn: string | null;
  yarns: ImportedYarnDraft[];
  needles: string | null;
  otherMaterials: string | null;
  stitchSample: string | null;
  gaugeWidth: number | null;
  gaugeHeight: number | null;
  fiberContent: string | null;
  colorway: string | null;
  weightCategory: string | null;
  steps: ImportedStepDraft[];
  imageUrls: string[];
  link: string | null;
  brand: string | null;
  extras: Record<string, unknown>;
}

export interface ImportedImageFacts {
  format: string;
  width: number;
  height: number;
}

export interface ImportedDocumentDraft {
  kind: ImportedDocumentKind;
  sha256: string;
  sourceUrl: string | null;
  project: ImportedProjectDraft;
  image: ImportedImageFacts | null;
  yarn: ImportedYarnDetails | null;
  pageKind: ImportedPageKind;
  /** Field path such as `yarn.brand` to where the value came from. */
  provenance: Record<string, ImportedFieldSource>;
  warnings: string[];
}

/** The outer preview response intentionally uses the Rust API's snake_case keys. */
export interface ImportPreview {
  draft: ImportedDocumentDraft;
  content_sha256: string;
  ai_requested: boolean;
  is_ai_enhanced: boolean;
  ai_fallback: boolean;
  /** The server's suggested stash yarn for an imported yarn (index into project.yarns). */
  yarn_matches?: ImportedYarnMatch[];
}

export interface ImportedYarnMatch {
  index: number;
  yarn_id: number;
  score: number;
  exact: boolean;
}

export const MAX_IMPORT_SOURCE_BYTES = 5 * 1024 * 1024;

export async function previewImportText(
  text: string,
  useAi: boolean,
  session: SessionData,
  signal?: AbortSignal,
): Promise<ImportPreview> {
  return previewImport({ type: "text", text, use_ai: useAi }, session, signal);
}

export type ImportTarget = "project" | "yarn";

export async function previewImportUrl(
  url: string,
  useAi: boolean,
  session: SessionData,
  signal?: AbortSignal,
  target: ImportTarget = "project",
): Promise<ImportPreview> {
  return previewImport(
    { type: "url", url, use_ai: useAi, target },
    session,
    signal,
  );
}

/** Same-origin thumbnail of a picture on an imported page; the browser never contacts the site. */
export function importImageSrc(url: string): string {
  return `/api/v2/imports/image?url=${encodeURIComponent(url)}`;
}

export async function previewImportFile(
  file: File,
  useAi: boolean,
  session: SessionData,
  signal?: AbortSignal,
): Promise<ImportPreview> {
  if (file.size > MAX_IMPORT_SOURCE_BYTES) {
    throw new ApiError(
      t("This document is larger than the 5 MiB import limit."),
      413,
      "payload_too_large",
    );
  }
  const body = new FormData();
  body.append("file", file);
  body.append("use_ai", String(useAi));
  const payload: unknown = await apiRequest("/api/v2/imports/preview-file", {
    method: "POST",
    csrfToken: session.csrf_token,
    body,
    signal,
  });
  return parseImportPreview(payload);
}

function previewImport(
  body:
    | { type: "url"; url: string; use_ai: boolean; target: ImportTarget }
    | {
        type: "text";
        text: string;
        use_ai: boolean;
      },
  session: SessionData,
  signal?: AbortSignal,
): Promise<ImportPreview> {
  if (
    body.type === "text" &&
    new TextEncoder().encode(body.text).byteLength > MAX_IMPORT_SOURCE_BYTES
  ) {
    return Promise.reject(
      new ApiError(
        t("This document is larger than the 5 MiB import limit."),
        413,
        "payload_too_large",
      ),
    );
  }
  return apiRequest<unknown>("/api/v2/imports/preview", {
    method: "POST",
    csrfToken: session.csrf_token,
    body,
    signal,
  }).then(parseImportPreview);
}

export function parseImportPreview(value: unknown): ImportPreview {
  if (!isRecord(value) || !isRecord(value.data)) throw invalidResponse();
  const data = value.data;
  if (
    typeof data.content_sha256 !== "string" ||
    typeof data.ai_requested !== "boolean" ||
    typeof data.is_ai_enhanced !== "boolean" ||
    typeof data.ai_fallback !== "boolean"
  )
    throw invalidResponse();
  return {
    draft: parseDocumentDraft(data.draft),
    content_sha256: data.content_sha256,
    ai_requested: data.ai_requested,
    is_ai_enhanced: data.is_ai_enhanced,
    ai_fallback: data.ai_fallback,
    yarn_matches: parseYarnMatches(data.yarn_matches),
  };
}

function parseDocumentDraft(value: unknown): ImportedDocumentDraft {
  if (
    !isRecord(value) ||
    !isDocumentKind(value.kind) ||
    typeof value.sha256 !== "string" ||
    !isNullableString(value.sourceUrl) ||
    !isRecord(value.project) ||
    !(value.image === null || isImageFacts(value.image))
  )
    throw invalidResponse();
  return {
    kind: value.kind,
    sha256: value.sha256,
    sourceUrl: value.sourceUrl,
    project: parseProjectDraft(value.project),
    image: value.image,
    yarn: isRecord(value.yarn) ? parseYarnDetails(value.yarn) : null,
    pageKind: isPageKind(value.pageKind) ? value.pageKind : "unknown",
    provenance: parseProvenance(value.provenance),
    warnings: isStringArray(value.warnings) ? value.warnings : [],
  };
}

function parseYarnDetails(value: Record<string, unknown>): ImportedYarnDetails {
  const text = (key: string): string | null =>
    typeof value[key] === "string" ? (value[key] as string) : null;
  const count = (key: string): number | null =>
    isNonnegativeInteger(value[key]) ? (value[key] as number) : null;
  return {
    name: text("name"),
    brand: text("brand"),
    colorway: text("colorway"),
    description: text("description"),
    fiberContent: text("fiberContent"),
    weightCategory: text("weightCategory"),
    weightGrams: count("weightGrams"),
    lengthMeters: count("lengthMeters"),
    recommendedNeedles: text("recommendedNeedles"),
    gauge: text("gauge"),
    care: text("care"),
    price: text("price"),
    link: text("link"),
    imageUrls: isStringArray(value.imageUrls) ? value.imageUrls : [],
  };
}

function parseProvenance(value: unknown): Record<string, ImportedFieldSource> {
  const result: Record<string, ImportedFieldSource> = {};
  if (!isRecord(value)) return result;
  for (const [key, source] of Object.entries(value)) {
    if (
      source === "text" ||
      source === "ai" ||
      source === "structured" ||
      source === "site"
    )
      result[key] = source;
  }
  return result;
}

function isPageKind(value: unknown): value is ImportedPageKind {
  return value === "yarn" || value === "pattern" || value === "unknown";
}

function parseProjectDraft(
  value: Record<string, unknown>,
): ImportedProjectDraft {
  if (
    !isNullableString(value.name) ||
    !isNullableString(value.description) ||
    !isNullableString(value.category) ||
    !isNullableString(value.yarn) ||
    !Array.isArray(value.yarns) ||
    !isNullableString(value.needles) ||
    !isNullableString(value.otherMaterials) ||
    !isNullableString(value.stitchSample) ||
    !isNullableInteger(value.gaugeWidth) ||
    !isNullableInteger(value.gaugeHeight) ||
    !isNullableString(value.fiberContent) ||
    !isNullableString(value.colorway) ||
    !isNullableString(value.weightCategory) ||
    !Array.isArray(value.steps) ||
    !isStringArray(value.imageUrls) ||
    !isNullableString(value.link) ||
    !isNullableString(value.brand) ||
    !isRecord(value.extras)
  )
    throw invalidResponse();
  return {
    designer: typeof value.designer === "string" ? value.designer : null,
    difficulty: typeof value.difficulty === "string" ? value.difficulty : null,
    sizes: typeof value.sizes === "string" ? value.sizes : null,
    tags: isStringArray(value.tags) ? value.tags : [],
    name: value.name,
    description: value.description,
    category: value.category,
    yarn: value.yarn,
    yarns: value.yarns.map(parseYarnDraft),
    needles: value.needles,
    otherMaterials: value.otherMaterials,
    stitchSample: value.stitchSample,
    gaugeWidth: value.gaugeWidth,
    gaugeHeight: value.gaugeHeight,
    fiberContent: value.fiberContent,
    colorway: value.colorway,
    weightCategory: value.weightCategory,
    steps: value.steps.map(parseStepDraft),
    imageUrls: value.imageUrls,
    link: value.link,
    brand: value.brand,
    extras: value.extras,
  };
}

function parseYarnDraft(value: unknown): ImportedYarnDraft {
  if (
    !isRecord(value) ||
    !isNullableString(value.name) ||
    !isNullableString(value.brand) ||
    !isNullableString(value.colorway) ||
    !isNullableString(value.weight) ||
    !isNullableString(value.length) ||
    !isNullableString(value.weightCategory) ||
    !isNullableString(value.fiberContent) ||
    !isNullableString(value.link) ||
    !isNullableString(value.imageUrl)
  )
    throw invalidResponse();
  return {
    name: value.name,
    brand: value.brand,
    colorway: value.colorway,
    weight: value.weight,
    length: value.length,
    weightCategory: value.weightCategory,
    fiberContent: value.fiberContent,
    link: value.link,
    imageUrl: value.imageUrl,
    amount: typeof value.amount === "string" ? value.amount : null,
    notes: typeof value.notes === "string" ? value.notes : null,
  };
}

function parseYarnMatches(value: unknown): ImportedYarnMatch[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) =>
    isRecord(item) &&
    isNonnegativeInteger(item.index) &&
    isNonnegativeInteger(item.yarn_id) &&
    typeof item.score === "number" &&
    typeof item.exact === "boolean"
      ? [
          {
            index: item.index,
            yarn_id: item.yarn_id,
            score: item.score,
            exact: item.exact,
          },
        ]
      : [],
  );
}

function parseStepDraft(value: unknown): ImportedStepDraft {
  if (
    !isRecord(value) ||
    !isNonnegativeInteger(value.stepNumber) ||
    !isNullableString(value.title) ||
    !isNullableString(value.description) ||
    !isStringArray(value.images)
  )
    throw invalidResponse();
  return {
    stepNumber: value.stepNumber,
    title: value.title,
    description: value.description,
    images: value.images,
  };
}

function isImageFacts(value: unknown): value is ImportedImageFacts {
  return (
    isRecord(value) &&
    typeof value.format === "string" &&
    isNonnegativeInteger(value.width) &&
    isNonnegativeInteger(value.height)
  );
}

function isDocumentKind(value: unknown): value is ImportedDocumentKind {
  return (
    value === "html" ||
    value === "plain_text" ||
    value === "markdown" ||
    value === "pdf" ||
    value === "image"
  );
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isNullableInteger(value: unknown): value is number | null {
  return value === null || isNonnegativeInteger(value);
}

function isNonnegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isStringArray(value: unknown): value is string[] {
  return (
    Array.isArray(value) && value.every((item) => typeof item === "string")
  );
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
