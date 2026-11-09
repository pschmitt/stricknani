import { t } from "#lib/i18n.svelte.ts";
import type {
  ProjectStepWrite,
  ProjectWrite,
} from "../projects/project-api.ts";
import {
  projectWriteFromDraft,
  type ProjectDraft,
} from "../projects/project-editor-state.ts";
import type { ImportPreview } from "./import-api";
import {
  createYarnWrite,
  importProjectEditor,
  type ImportAssetChoice,
  type ImportImageChoice,
  type ImportYarnEditor,
} from "./import-draft";

/**
 * What an import adds to the new-project editor beyond the editor's own
 * fields: the picture and file suggestions to choose from, the pictures of
 * each step and the yarns the pattern lists, each with its own choice.
 */
export interface ImportEditorState {
  /** The server draft the editor was filled from; deleted when the project is saved. */
  draftId: number | null;
  isAiEnhanced: boolean;
  aiFallback: boolean;
  warnings: string[];
  provenance: Record<string, string>;
  imageUrls: ImportImageChoice[];
  assets: ImportAssetChoice[];
  /** Suggested pictures per step, by the editor's step key. */
  stepImages: Record<number, ImportImageChoice[]>;
  yarns: ImportYarnEditor[];
}

/** The editor's fields and the import state for a finished preview. */
export function projectFromImport(
  preview: ImportPreview,
  draftId: number | null,
  nextKey: () => number,
  fallbackStepTitle: (number: number) => string,
): { draft: ProjectDraft; state: ImportEditorState } {
  const editor = importProjectEditor(preview);
  const stepImages: Record<number, ImportImageChoice[]> = {};
  const steps = editor.steps.map((step, index) => {
    const key = nextKey();
    if (step.images.length) stepImages[key] = step.images;
    return {
      key,
      title:
        step.title.trim() || fallbackStepTitle(step.stepNumber || index + 1),
      description: step.description,
    };
  });
  return {
    draft: {
      name: editor.name,
      category: editor.category,
      needles: editor.needles,
      stitchSample: editor.stitchSample,
      description: editor.description,
      notes: editor.notes,
      otherMaterials: editor.otherMaterials,
      tags: [],
      link: editor.link,
      yarnIds: [],
      steps,
    },
    state: {
      draftId,
      isAiEnhanced: preview.is_ai_enhanced,
      aiFallback: preview.ai_fallback,
      warnings: preview.draft.warnings,
      provenance: preview.draft.provenance,
      imageUrls: editor.imageUrls,
      assets: editor.assets,
      stepImages,
      yarns: editor.yarns,
    },
  };
}

/** Stash yarns chosen for the imported yarns, once each. */
export function importedStashIds(state: ImportEditorState): number[] {
  return [
    ...new Set(
      state.yarns
        .filter((yarn) => yarn.choice === "stash" && yarn.stashId !== null)
        .map((yarn) => yarn.stashId as number),
    ),
  ];
}

/**
 * The create request of a project that was filled from an import: the
 * editor's fields plus the chosen pictures and files, the step pictures and
 * the yarns. Suggestions that are not attached stay reachable as links in the
 * notes.
 */
export function importedProjectWrite(
  draft: ProjectDraft,
  state: ImportEditorState,
  allowExternalImages: boolean,
  amountLabel = "Amount",
): ProjectWrite {
  const base = projectWriteFromDraft(draft, state.isAiEnhanced);
  const chosen = (images: ImportImageChoice[]): string[] =>
    allowExternalImages
      ? images
          .filter((image) => image.selected)
          .map((image) => image.url.trim())
          .filter(Boolean)
      : [];
  const steps: ProjectStepWrite[] = base.steps.map((step, index) => {
    const urls = chosen(state.stepImages[draft.steps[index]?.key ?? -1] ?? []);
    return urls.length ? { ...step, image_urls: urls } : step;
  });
  const imageUrls = chosen(state.imageUrls);
  const attachmentUrls = state.assets
    .filter((asset) => asset.selected && allowExternalImages)
    .map((asset) => ({
      url: asset.url.trim(),
      kind: asset.kind,
      label: optionalText(asset.label),
    }))
    .filter((asset) => asset.url);
  const notes = [
    base.notes?.trim() ?? "",
    unattachedNotes(draft, state, allowExternalImages),
  ]
    .filter(Boolean)
    .join("\n\n");
  return {
    ...base,
    notes: optionalText(notes),
    yarn_ids: [...new Set([...base.yarn_ids, ...importedStashIds(state)])],
    new_yarns: state.yarns
      .filter((yarn) => yarn.choice === "new" && yarn.name.trim())
      .map((yarn) =>
        createYarnWrite(
          yarn,
          state.isAiEnhanced,
          allowExternalImages,
          amountLabel,
        ),
      ),
    steps,
    ...(imageUrls.length ? { image_urls: imageUrls } : {}),
    ...(attachmentUrls.length ? { attachment_urls: attachmentUrls } : {}),
  };
}

function unattachedNotes(
  draft: ProjectDraft,
  state: ImportEditorState,
  allowExternalImages: boolean,
): string {
  const lines: string[] = [];
  for (const image of state.imageUrls) {
    if (!image.selected || !allowExternalImages)
      lines.push(`${t("Project image URL not attached")}: ${image.url}`);
  }
  draft.steps.forEach((step, index) => {
    for (const image of state.stepImages[step.key] ?? []) {
      if (!image.selected || !allowExternalImages)
        lines.push(
          `${t("Step image URL not attached")} (${index + 1}): ${image.url}`,
        );
    }
  });
  // Diagrams and PDFs that are not stored stay reachable as links.
  for (const asset of state.assets) {
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

function optionalText(value: string): string | null {
  const trimmed = value.trim();
  return trimmed || null;
}
