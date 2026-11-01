import type {
  ProjectStepWrite,
  ProjectWrite,
} from "../projects/project-api.ts";
import type { YarnWrite } from "../yarns/yarn-api.ts";
import { t } from "#lib/i18n.svelte.ts";
import type {
  ImportPreview,
  ImportedProjectDraft,
  ImportedStepDraft,
  ImportedYarnDraft,
} from "./import-api";

export interface ImportImageChoice {
  url: string;
  selected: boolean;
}

export interface ImportStepEditor {
  stepNumber: number;
  title: string;
  description: string;
  images: ImportImageChoice[];
}

export interface ImportYarnEditor {
  source: ImportedYarnDraft;
  name: string;
  brand: string;
  colorway: string;
  fiberContent: string;
  weightCategory: string;
  link: string;
  weightGrams: string;
  lengthMeters: string;
  notes: string;
  createdId: number | null;
  selectedForProject: boolean;
}

export interface ImportProjectEditor {
  name: string;
  description: string;
  category: string;
  needles: string;
  stitchSample: string;
  otherMaterials: string;
  notes: string;
  additionalDetails: string;
  brand: string;
  fiberContent: string;
  colorway: string;
  weightCategory: string;
  gaugeWidth: number | null;
  gaugeHeight: number | null;
  link: string;
  imageUrls: ImportImageChoice[];
  steps: ImportStepEditor[];
  yarns: ImportYarnEditor[];
}

export function importProjectEditor(
  preview: ImportPreview,
): ImportProjectEditor {
  const project = preview.draft.project;
  const yarns = [...project.yarns];
  if (
    project.yarn?.trim() &&
    !yarns.some((yarn) => yarn.name === project.yarn)
  ) {
    yarns.unshift({
      name: project.yarn,
      brand: null,
      colorway: null,
      weight: null,
      length: null,
      weightCategory: null,
      fiberContent: project.fiberContent,
      link: null,
      imageUrl: null,
    });
  }
  return {
    name: project.name ?? "",
    description: project.description ?? "",
    category: project.category ?? "",
    needles: project.needles ?? "",
    stitchSample: project.stitchSample ?? "",
    otherMaterials: project.otherMaterials ?? "",
    notes: "",
    additionalDetails: additionalProjectDetails(project),
    brand: project.brand ?? "",
    fiberContent: project.fiberContent ?? "",
    colorway: project.colorway ?? "",
    weightCategory: project.weightCategory ?? "",
    gaugeWidth: project.gaugeWidth,
    gaugeHeight: project.gaugeHeight,
    link: project.link ?? preview.draft.sourceUrl ?? "",
    imageUrls: project.imageUrls.map((url) => ({
      url,
      selected: false,
    })),
    steps: project.steps.map(importStepEditor),
    yarns: yarns.map(importYarnEditor),
  };
}

export function createProjectWrite(
  draft: ImportProjectEditor,
  isAiEnhanced: boolean,
  allowExternalImages: boolean,
  fallbackStepTitle: (number: number) => string,
): ProjectWrite {
  const stepImages = draft.steps.map((step) =>
    step.images
      .filter((image) => image.selected && allowExternalImages)
      .map((image) => image.url.trim())
      .filter(Boolean),
  );
  const extraNotes = [
    draft.additionalDetails.trim(),
    unattachedImageNotes(draft, allowExternalImages),
  ].filter(Boolean);
  const notes = [draft.notes.trim(), ...extraNotes]
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
    yarn_ids: draft.yarns
      .filter((yarn) => yarn.createdId !== null && yarn.selectedForProject)
      .map((yarn) => yarn.createdId as number),
    steps,
    ...(imageUrls.length ? { image_urls: imageUrls } : {}),
  };
}

export function createYarnWrite(
  draft: ImportYarnEditor,
  isAiEnhanced: boolean,
): YarnWrite {
  const notes = [draft.notes.trim(), sourceYarnNotes(draft.source)]
    .filter(Boolean)
    .join("\n\n");
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

function importYarnEditor(source: ImportedYarnDraft): ImportYarnEditor {
  return {
    source,
    name: source.name ?? "",
    brand: source.brand ?? "",
    colorway: source.colorway ?? "",
    fiberContent: source.fiberContent ?? "",
    weightCategory: source.weightCategory ?? "",
    link: source.link ?? "",
    weightGrams: parseMeasurement(source.weight, "g"),
    lengthMeters: parseMeasurement(source.length, "m"),
    notes: "",
    createdId: null,
    selectedForProject: true,
  };
}

function additionalProjectDetails(project: ImportedProjectDraft): string {
  const lines: string[] = [];
  const add = (label: string, value: string | number | null) => {
    if (value !== null && String(value).trim())
      lines.push(`${label}: ${value}`);
  };
  add(t("Imported yarn"), project.yarn);
  add(t("Brand"), project.brand);
  add(t("Fiber content"), project.fiberContent);
  add(t("Colorway"), project.colorway);
  add(t("Weight category"), project.weightCategory);
  add(t("Gauge width"), project.gaugeWidth);
  add(t("Gauge height"), project.gaugeHeight);
  for (const yarn of project.yarns) {
    const facts = [
      yarn.name,
      yarn.brand,
      yarn.colorway,
      yarn.weight,
      yarn.length,
      yarn.weightCategory,
      yarn.fiberContent,
      yarn.link,
      yarn.imageUrl,
    ].filter((value): value is string => Boolean(value?.trim()));
    if (facts.length)
      lines.push(`${t("Imported yarn details")}: ${facts.join(" · ")}`);
  }
  for (const [key, value] of Object.entries(project.extras)) {
    lines.push(`${key}: ${formatExtra(value)}`);
  }
  return lines.join("\n");
}

function sourceYarnNotes(source: ImportedYarnDraft): string {
  const lines = [];
  if (source.weight?.trim())
    lines.push(`${t("Imported weight text")}: ${source.weight}`);
  if (source.length?.trim())
    lines.push(`${t("Imported length text")}: ${source.length}`);
  if (source.imageUrl?.trim())
    lines.push(
      `${t("Imported image URL suggestion (not attached)")}: ${source.imageUrl}`,
    );
  return lines.join("\n");
}

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
