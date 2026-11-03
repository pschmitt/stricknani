import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
import type {
  ChangeItemV1,
  ChangeValue,
} from "#lib/features/admin/admin-api.ts";
import { wordDiff, type DiffSegment } from "#lib/features/admin/word-diff.ts";

export type ChangeKind = "added" | "removed" | "changed";

export function displayValue(value: ChangeValue, locale: Locale): string {
  switch (value.type) {
    case "text":
    case "integer":
    case "boolean":
    case "legacy_text":
      return value.value === null
        ? t("Not provided", locale)
        : String(value.value);
    case "text_list":
    case "yarns":
      return value.value.length
        ? value.value.join("\n")
        : t("Empty list", locale);
    case "steps":
      return value.value.length
        ? value.value
            .map(
              (step) =>
                `${step.number}. ${step.title}${step.description ? `\n${step.description}` : ""}`,
            )
            .join("\n\n")
        : t("Empty list", locale);
    case "link_changed":
      return value.value
        ? t("Link present", locale)
        : t("Link not present", locale);
  }
}

export function isEmptyValue(value: ChangeValue): boolean {
  switch (value.type) {
    case "text":
    case "integer":
      return value.value === null;
    case "legacy_text":
      return value.value === "";
    case "text_list":
    case "yarns":
    case "steps":
      return value.value.length === 0;
    default:
      return false;
  }
}

export function changeKind(item: ChangeItemV1): ChangeKind {
  if (isEmptyValue(item.before) && !isEmptyValue(item.after)) return "added";
  if (!isEmptyValue(item.before) && isEmptyValue(item.after)) return "removed";
  return "changed";
}

export function kindLabel(kind: ChangeKind, locale: Locale): string {
  return kind === "added"
    ? t("Added", locale)
    : kind === "removed"
      ? t("Removed", locale)
      : t("Changed", locale);
}

/** Word-level highlights for both sides; whole values when there is nothing to compare. */
export function segments(
  item: ChangeItemV1,
  locale: Locale,
): { before: DiffSegment[]; after: DiffSegment[] } {
  const before = displayValue(item.before, locale);
  const after = displayValue(item.after, locale);
  const comparable =
    !isEmptyValue(item.before) &&
    !isEmptyValue(item.after) &&
    item.before.type !== "boolean" &&
    item.before.type !== "link_changed";
  if (!comparable) {
    return {
      before: [{ text: before, changed: false }],
      after: [{ text: after, changed: false }],
    };
  }
  return wordDiff(before, after);
}

/** "+2" / "−3" for counts and other numbers. */
export function delta(item: ChangeItemV1): string | null {
  if (item.before.type !== "integer" || item.after.type !== "integer")
    return null;
  if (item.before.value === null || item.after.value === null) return null;
  const difference = item.after.value - item.before.value;
  if (difference === 0) return null;
  return difference > 0 ? `+${difference}` : `−${-difference}`;
}

export function fieldLabel(field: string, locale: Locale): string {
  const labels: Record<string, string> = {
    "project.name": msg("Project name"),
    "project.category": msg("Category"),
    "project.yarn": msg("Yarn"),
    "project.yarns": msg("Linked yarns"),
    "project.needles": msg("Needles"),
    "project.stitch_sample": msg("Stitch sample"),
    "project.description": msg("Description"),
    "project.notes": msg("Notes"),
    "project.other_materials": msg("Other materials"),
    "project.tags": msg("Tags"),
    "project.steps": msg("Steps"),
    "project.step_count": msg("Step count"),
    "project.image_count": msg("Image count"),
    "project.attachment_count": msg("Attachment count"),
    "project.is_ai_enhanced": msg("AI enhanced"),
    "project.link": msg("Original source link"),
    "yarn.name": msg("Yarn name"),
    "yarn.description": msg("Description"),
    "yarn.brand": msg("Brand"),
    "yarn.colorway": msg("Colorway"),
    "yarn.dye_lot": msg("Dye lot"),
    "yarn.fiber_content": msg("Fiber content"),
    "yarn.weight_category": msg("Weight category"),
    "yarn.recommended_needles": msg("Recommended needles"),
    "yarn.weight_grams": msg("Weight"),
    "yarn.length_meters": msg("Length"),
    "yarn.notes": msg("Notes"),
    "yarn.photo_count": msg("Photo count"),
    "yarn.is_ai_enhanced": msg("AI enhanced"),
    "yarn.link": msg("Original source link"),
    "category.name": msg("Category name"),
  };
  return t(labels[field] ?? msg("Recorded field"), locale);
}
