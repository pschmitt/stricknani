import type {
  ProjectStepWrite,
  ProjectWrite,
} from "../projects/project-api.ts";
import type { YarnWrite } from "../yarns/yarn-api.ts";
import { t } from "#lib/i18n.svelte.ts";
import type {
  ImportPreview,
  ImportedAsset,
  ImportedProjectDraft,
  ImportedStepDraft,
  ImportedYarnDetails,
  ImportedYarnDraft,
} from "./import-api";

/** How many suggested pictures start out selected. */
const PRESELECTED_IMAGES = 3;

export interface ImportImageChoice {
  url: string;
  selected: boolean;
}

export interface ImportAssetChoice {
  url: string;
  kind: ImportedAsset["kind"];
  label: string;
  selected: boolean;
}

export interface ImportStepEditor {
  stepNumber: number;
  title: string;
  description: string;
  images: ImportImageChoice[];
}

/** What to do with a yarn the import found. */
export type YarnChoice = "stash" | "new" | "skip";

export interface ImportYarnEditor {
  source: ImportedYarnDraft;
  /** Use a stash yarn, create a new one from these fields, or leave the yarn out. */
  choice: YarnChoice;
  /** The stash yarn to link when the choice is "stash". */
  stashId: number | null;
  /** The server's suggested match, if any, for the "Suggested" badge. */
  suggestedId: number | null;
  name: string;
  brand: string;
  colorway: string;
  amount: string;
  fiberContent: string;
  weightCategory: string;
  link: string;
  weightGrams: string;
  lengthMeters: string;
  notes: string;
  imageUrl: string;
}

export interface ImportProjectEditor {
  name: string;
  description: string;
  category: string;
  needles: string;
  stitchSample: string;
  otherMaterials: string;
  /** The one notes field: it starts with the extracted details that have no field of their own. */
  notes: string;
  link: string;
  imageUrls: ImportImageChoice[];
  assets: ImportAssetChoice[];
  steps: ImportStepEditor[];
  yarns: ImportYarnEditor[];
  /** Stash yarns the user linked in addition to the ones the import found. */
  extraStashIds: number[];
}

export function importProjectEditor(
  preview: ImportPreview,
): ImportProjectEditor {
  const project = preview.draft.project;
  const yarns = [...project.yarns];
  // Servers that predate the clean yarn list only send the loose "yarn" text.
  if (!yarns.length && project.yarn?.trim()) {
    yarns.push({
      name: project.yarn,
      brand: project.brand,
      colorway: project.colorway,
      weight: null,
      length: null,
      weightCategory: project.weightCategory,
      fiberContent: project.fiberContent,
      link: null,
      imageUrl: null,
    });
  }
  const matches = new Map(
    (preview.yarn_matches ?? []).map((match) => [match.index, match.yarn_id]),
  );
  return {
    name: project.name ?? "",
    description: project.description ?? "",
    category: project.category ?? "",
    needles: project.needles ?? "",
    stitchSample: project.stitchSample ?? "",
    otherMaterials: project.otherMaterials ?? "",
    notes: leftoverDetails(project),
    link: project.link ?? preview.draft.sourceUrl ?? "",
    imageUrls: project.imageUrls.map((url, index) => ({
      url,
      selected: index < PRESELECTED_IMAGES,
    })),
    assets: project.assets.map((asset) => ({
      url: asset.url,
      kind: asset.kind,
      label: asset.label ?? "",
      selected: true,
    })),
    steps: project.steps.map(importStepEditor),
    yarns: yarns.map((yarn, index) =>
      importYarnEditor(yarn, matches.get(index) ?? null),
    ),
    extraStashIds: [],
  };
}

/** Stash yarns to link: the chosen ones for imported yarns plus the extra ones, once each. */
export function linkedStashIds(draft: ImportProjectEditor): number[] {
  const ids = draft.yarns
    .filter((yarn) => yarn.choice === "stash" && yarn.stashId !== null)
    .map((yarn) => yarn.stashId as number);
  return [...new Set([...ids, ...draft.extraStashIds])];
}

export function createProjectWrite(
  draft: ImportProjectEditor,
  isAiEnhanced: boolean,
  allowExternalImages: boolean,
  fallbackStepTitle: (number: number) => string,
  amountLabel = "Amount",
): ProjectWrite {
  const stepImages = draft.steps.map((step) =>
    step.images
      .filter((image) => image.selected && allowExternalImages)
      .map((image) => image.url.trim())
      .filter(Boolean),
  );
  const notes = [
    draft.notes.trim(),
    unattachedImageNotes(draft, allowExternalImages),
  ]
    .filter(Boolean)
    .join("\n\n");
  const steps: ProjectStepWrite[] = draft.steps.map((step, index) => ({
    title: step.title.trim() || fallbackStepTitle(step.stepNumber || index + 1),
    description: optionalText(step.description),
    step_number: step.stepNumber || index + 1,
    ...(stepImages[index].length ? { image_urls: stepImages[index] } : {}),
  }));
  const imageUrls = draft.imageUrls
    .filter((image) => image.selected && allowExternalImages)
    .map((image) => image.url.trim())
    .filter(Boolean);
  const attachmentUrls = draft.assets
    .filter((asset) => asset.selected && allowExternalImages)
    .map((asset) => ({
      url: asset.url.trim(),
      kind: asset.kind,
      label: optionalText(asset.label),
    }))
    .filter((asset) => asset.url);
  return {
    name: draft.name.trim(),
    category: optionalText(draft.category),
    needles: optionalText(draft.needles),
    stitch_sample: optionalText(draft.stitchSample),
    description: optionalText(draft.description),
    notes: optionalText(notes),
    other_materials: optionalText(draft.otherMaterials),
    tags: [],
    link: optionalText(draft.link),
    is_ai_enhanced: isAiEnhanced,
    yarn_ids: linkedStashIds(draft),
    new_yarns: draft.yarns
      .filter((yarn) => yarn.choice === "new" && yarn.name.trim())
      .map((yarn) =>
        createYarnWrite(yarn, isAiEnhanced, allowExternalImages, amountLabel),
      ),
    steps,
    ...(imageUrls.length ? { image_urls: imageUrls } : {}),
    ...(attachmentUrls.length ? { attachment_urls: attachmentUrls } : {}),
  };
}

export function createYarnWrite(
  draft: ImportYarnEditor,
  isAiEnhanced: boolean,
  allowExternalImages = true,
  amountLabel = "Amount",
): YarnWrite {
  const notes = [
    draft.notes.trim(),
    draft.amount.trim() ? `${amountLabel}: ${draft.amount.trim()}` : "",
  ]
    .filter(Boolean)
    .join("\n\n");
  const imageUrl = draft.imageUrl.trim();
  return {
    name: draft.name.trim(),
    description: null,
    brand: optionalText(draft.brand),
    colorway: optionalText(draft.colorway),
    dye_lot: null,
    fiber_content: optionalText(draft.fiberContent),
    weight_category: optionalText(draft.weightCategory),
    recommended_needles: null,
    weight_grams: parseNonnegativeInteger(draft.weightGrams),
    length_meters: parseNonnegativeInteger(draft.lengthMeters),
    notes: optionalText(notes),
    link: optionalText(draft.link),
    is_ai_enhanced: isAiEnhanced,
    ...(allowExternalImages && isHttpUrl(imageUrl)
      ? { image_urls: [imageUrl] }
      : {}),
  };
}

export function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (
      (url.protocol === "http:" || url.protocol === "https:") &&
      !url.username &&
      !url.password &&
      Boolean(url.hostname)
    );
  } catch {
    return false;
  }
}

export function parseMeasurement(
  value: string | null,
  unit: "g" | "m",
): string {
  if (!value) return "";
  const unitPattern = unit === "g" ? "g|gram(?:s)?" : "m|meter(?:s)?";
  const match = value
    .trim()
    .match(new RegExp(`^(\\d+)\\s*(?:${unitPattern})?$`, "i"));
  return match ? match[1] : "";
}

function importStepEditor(step: ImportedStepDraft): ImportStepEditor {
  return {
    stepNumber: step.stepNumber,
    title: step.title ?? "",
    description: step.description ?? "",
    images: step.images.map((url) => ({ url, selected: false })),
  };
}

function importYarnEditor(
  source: ImportedYarnDraft,
  suggestedId: number | null,
): ImportYarnEditor {
  return {
    source,
    choice: suggestedId !== null ? "stash" : "new",
    stashId: suggestedId,
    suggestedId,
    name: source.name ?? "",
    brand: source.brand ?? "",
    colorway: source.colorway ?? "",
    amount: source.amount ?? "",
    fiberContent: source.fiberContent ?? "",
    weightCategory: source.weightCategory ?? "",
    link: source.link ?? "",
    weightGrams: parseMeasurement(source.weight, "g"),
    lengthMeters: parseMeasurement(source.length, "m"),
    notes: source.notes ?? "",
    imageUrl: source.imageUrl ?? "",
  };
}

/**
 * Extracted details that have no field of their own. Yarn facts (name, brand, fibre, weight)
 * belong to the yarn section; designer, sizes and similar stay here as plain lines.
 */
function leftoverDetails(project: ImportedProjectDraft): string {
  const lines: string[] = [];
  const add = (label: string, value: string | number | null) => {
    if (value !== null && String(value).trim())
      lines.push(`${label}: ${value}`);
  };
  add(t("Designer"), project.designer);
  add(t("Sizes"), project.sizes);
  add(t("Difficulty"), project.difficulty);
  add(t("Gauge width"), project.gaugeWidth);
  add(t("Gauge height"), project.gaugeHeight);
  for (const [key, value] of Object.entries(project.extras)) {
    if (INTERNAL_EXTRAS.has(key)) continue;
    lines.push(`${key}: ${formatExtra(value)}`);
  }
  return lines.join("\n");
}

const INTERNAL_EXTRAS = new Set(["page_text", "structured_data", "page_links"]);

function unattachedImageNotes(
  draft: ImportProjectEditor,
  allowExternalImages: boolean,
): string {
  const lines: string[] = [];
  for (const image of draft.imageUrls) {
    if (!image.selected || !allowExternalImages)
      lines.push(`${t("Project image URL not attached")}: ${image.url}`);
  }
  for (const step of draft.steps) {
    for (const image of step.images) {
      if (!image.selected || !allowExternalImages)
        lines.push(
          `${t("Step image URL not attached")} (${step.stepNumber}): ${image.url}`,
        );
    }
  }
  // Diagrams and PDFs that are not stored stay reachable as links.
  for (const asset of draft.assets) {
    if (asset.selected && allowExternalImages) continue;
    const prefix =
      asset.kind === "pdf"
        ? t("PDF link not attached")
        : t("Diagram URL not attached");
    const label = asset.label ? `${asset.label} - ` : "";
    lines.push(`${prefix}: ${label}${asset.url}`);
  }
  return lines.join("\n");
}

function formatExtra(value: unknown): string {
  if (typeof value === "string") return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function parseNonnegativeInteger(value: string): number | null {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const parsed = Number(trimmed);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function optionalText(value: string): string | null {
  const trimmed = value.trim();
  return trimmed || null;
}

/** Review state for a yarn import (the "From a link" yarn flow). */
export interface YarnImportEditor {
  name: string;
  brand: string;
  colorway: string;
  description: string;
  fiberContent: string;
  weightCategory: string;
  weightGrams: string;
  lengthMeters: string;
  recommendedNeedles: string;
  notes: string;
  link: string;
  images: ImportImageChoice[];
}

export function yarnImportEditor(
  preview: ImportPreview,
  fallbackLink = "",
): YarnImportEditor {
  const yarn: ImportedYarnDetails = preview.draft.yarn ?? {
    name: preview.draft.project.name,
    brand: null,
    colorway: null,
    description: null,
    fiberContent: null,
    weightCategory: null,
    weightGrams: null,
    lengthMeters: null,
    recommendedNeedles: null,
    gauge: null,
    care: null,
    price: null,
    link: null,
    imageUrls: [],
  };
  const notes = [
    yarn.gauge ? `${t("Gauge")}: ${yarn.gauge}` : "",
    yarn.care ? `${t("Care")}: ${yarn.care}` : "",
    yarn.price ? `${t("Price")}: ${yarn.price}` : "",
  ]
    .filter(Boolean)
    .join("\n");
  return {
    name: yarn.name ?? "",
    brand: yarn.brand ?? "",
    colorway: yarn.colorway ?? "",
    description: yarn.description ?? "",
    fiberContent: yarn.fiberContent ?? "",
    weightCategory: yarn.weightCategory ?? "",
    weightGrams: yarn.weightGrams === null ? "" : String(yarn.weightGrams),
    lengthMeters: yarn.lengthMeters === null ? "" : String(yarn.lengthMeters),
    recommendedNeedles: yarn.recommendedNeedles ?? "",
    notes,
    link: yarn.link ?? preview.draft.sourceUrl ?? fallbackLink,
    images: yarn.imageUrls.map((url, index) => ({
      url,
      selected: index < PRESELECTED_IMAGES,
    })),
  };
}

export function createYarnImportWrite(
  draft: YarnImportEditor,
  isAiEnhanced: boolean,
  allowExternalImages: boolean,
): YarnWrite {
  const imageUrls = allowExternalImages
    ? draft.images
        .filter((image) => image.selected)
        .map((image) => image.url.trim())
        .filter(Boolean)
    : [];
  return {
    name: draft.name.trim(),
    description: optionalText(draft.description),
    brand: optionalText(draft.brand),
    colorway: optionalText(draft.colorway),
    dye_lot: null,
    fiber_content: optionalText(draft.fiberContent),
    weight_category: optionalText(draft.weightCategory),
    recommended_needles: optionalText(draft.recommendedNeedles),
    weight_grams: parseNonnegativeInteger(draft.weightGrams),
    length_meters: parseNonnegativeInteger(draft.lengthMeters),
    notes: optionalText(draft.notes),
    link: optionalText(draft.link),
    is_ai_enhanced: isAiEnhanced,
    ...(imageUrls.length ? { image_urls: imageUrls } : {}),
  };
}

export type FieldHint = "ai" | "guessed" | "";

/** Where a value came from, for the small badge next to a field. Markup and site data get none. */
export function fieldHint(
  provenance: Record<string, string>,
  key: string,
  browsed = false,
): FieldHint {
  const source = provenance[key];
  // Read by the AI from the live page: nothing on our side can verify it.
  if (source === "ai") return browsed ? "guessed" : "ai";
  if (source === "text") return "guessed";
  return "";
}

/** Explanations for the machine codes in `draft.warnings`. */
export function importWarningMessage(
  code: string,
  locale: "en" | "de",
): string | null {
  switch (code) {
    case "looks_like_yarn_page":
      return t(
        "This looks like a yarn page. Import it as a yarn instead?",
        locale,
      );
    case "looks_like_pattern_page":
    case "not_a_yarn_page":
      return t(
        "This looks like a pattern page, not a yarn. Check the fields below.",
        locale,
      );
    case "ai_browsed":
      return t(
        "The AI read this page in a browser. Check the fields marked Guessed before saving.",
        locale,
      );
    case "yarn_enrichment_failed":
      return t(
        "Some yarn details could not be loaded from their product pages. Check the yarns below.",
        locale,
      );
    case "little_page_text":
      return t(
        "This page has little readable text. It may load its content with JavaScript, so some details can be missing.",
        locale,
      );
    default:
      return null;
  }
}
