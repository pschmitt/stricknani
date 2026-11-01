import type { ProjectDetail, ProjectWrite } from "./project-api";

export interface ProjectStepDraft {
  key: number;
  id?: number;
  title: string;
  description: string;
}

export interface ProjectDraft {
  name: string;
  category: string;
  needles: string;
  stitchSample: string;
  description: string;
  notes: string;
  otherMaterials: string;
  tags: string[];
  link: string;
  yarnIds: number[];
  steps: ProjectStepDraft[];
}

export interface ProjectDraftErrors {
  name?: string;
  link?: string;
  steps?: string;
}

export function blankProjectDraft(): ProjectDraft {
  return {
    name: "",
    category: "",
    needles: "",
    stitchSample: "",
    description: "",
    notes: "",
    otherMaterials: "",
    tags: [],
    link: "",
    yarnIds: [],
    steps: [],
  };
}

export function projectDraftFromDetail(
  project: ProjectDetail,
  nextKey: () => number,
): ProjectDraft {
  return {
    name: project.name,
    category: project.category ?? "",
    needles: project.needles ?? "",
    stitchSample: project.stitch_sample ?? "",
    description: project.description ?? "",
    notes: project.notes ?? "",
    otherMaterials: project.other_materials ?? "",
    tags: [...project.tags],
    link: project.link ?? "",
    yarnIds: [...project.yarn_ids],
    steps: [...project.steps]
      .sort((left, right) => left.step_number - right.step_number)
      .map((step) => ({
        key: nextKey(),
        id: step.id,
        title: step.title,
        description: step.description ?? "",
      })),
  };
}

export function projectWriteFromDraft(
  draft: ProjectDraft,
  isAiEnhanced: boolean,
): ProjectWrite {
  return {
    name: draft.name.trim(),
    category: optionalText(draft.category),
    needles: optionalText(draft.needles),
    stitch_sample: optionalText(draft.stitchSample),
    description: optionalText(draft.description),
    notes: optionalText(draft.notes),
    other_materials: optionalText(draft.otherMaterials),
    tags: [...draft.tags],
    link: optionalText(draft.link),
    is_ai_enhanced: isAiEnhanced,
    yarn_ids: [...new Set(draft.yarnIds)],
    steps: draft.steps.map((step, index) => ({
      ...(step.id === undefined ? {} : { id: step.id }),
      title: step.title.trim(),
      description: optionalText(step.description),
      step_number: index + 1,
    })),
  };
}

export function validateProjectDraft(draft: ProjectDraft): ProjectDraftErrors {
  const errors: ProjectDraftErrors = {};
  const name = draft.name.trim();
  if (!name) errors.name = "required";
  else if (name.length > 256) errors.name = "too_long";

  const link = draft.link.trim();
  if (link) {
    try {
      const url = new URL(link);
      if (url.protocol !== "https:" && url.protocol !== "http:")
        throw new Error("scheme");
    } catch {
      errors.link = "invalid_url";
    }
  }

  if (
    draft.steps.some((step) => {
      const title = step.title.trim();
      return !title || title.length > 256;
    })
  )
    errors.steps = "invalid_steps";

  return errors;
}

function optionalText(value: string): string | null {
  const cleaned = value.trim();
  return cleaned.length > 0 ? cleaned : null;
}
