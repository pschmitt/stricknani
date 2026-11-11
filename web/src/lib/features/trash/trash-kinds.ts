import { msg } from "#lib/i18n.svelte.ts";

/** Every kind of item the trash can hold. */
export const TRASH_KINDS = [
  "project",
  "yarn",
  "category",
  "step",
  "image",
  "attachment",
  "photo",
] as const;

export type TrashKind = (typeof TRASH_KINDS)[number];

/** The kinds that live below a project or yarn and come back with it. */
export type TrashParentKind = "project" | "yarn";

/** Plural path segment of `/api/v2/trash/{segment}/{id}`. */
const SEGMENTS: Record<TrashKind, string> = {
  project: "projects",
  yarn: "yarns",
  category: "categories",
  step: "steps",
  image: "images",
  attachment: "attachments",
  photo: "photos",
};

export function isTrashKind(value: unknown): value is TrashKind {
  return (
    typeof value === "string" &&
    (TRASH_KINDS as readonly string[]).includes(value)
  );
}

export function trashSegment(kind: TrashKind): string {
  return SEGMENTS[kind];
}

/** Singular label per kind. Message IDs stay literal for the extractor. */
const KIND_LABELS: Record<TrashKind, string> = {
  project: msg("Project"),
  yarn: msg("Yarn"),
  category: msg("Category"),
  step: msg("Step"),
  image: msg("Image"),
  attachment: msg("Attachment"),
  photo: msg("Photo"),
};

/** English source message of the kind label; pass it to `t()`. */
export function trashKindLabelKey(kind: TrashKind): string {
  return KIND_LABELS[kind];
}
