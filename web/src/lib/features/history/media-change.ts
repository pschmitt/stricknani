import type {
  ChangeItemV1,
  ChangeValue,
} from "#lib/features/admin/admin-api.ts";

/** URLs of a current picture that a change label refers to (`media_thumbnails` of a history or admin audit event). */
export interface ChangeMediaEntry {
  url: string;
  thumbnail_url: string;
}

export type ChangeMedia = Record<string, ChangeMediaEntry>;

/** The change fields whose values are picture labels. */
const MEDIA_FIELDS = new Set([
  "project.cover_image",
  "project.image_order",
  "project.step_image_order",
  "yarn.primary_photo",
  "yarn.photo_order",
]);

const MAX_ENTRIES = 500;

function safePath(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length <= 512 &&
    (value.startsWith("/media/") ||
      value.startsWith("/api/v2/admin/resources/")) &&
    !value.includes("..")
  );
}

/** Same-origin picture links only; anything malformed is dropped, never fatal. */
export function parseChangeMedia(value: unknown): ChangeMedia | null {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return null;
  const result: ChangeMedia = Object.create(null) as ChangeMedia;
  let count = 0;
  for (const [label, entry] of Object.entries(value)) {
    if (count >= MAX_ENTRIES) break;
    if (typeof entry !== "object" || entry === null) continue;
    const { url, thumbnail_url: thumbnail } = entry as Record<string, unknown>;
    if (!safePath(url) || !safePath(thumbnail)) continue;
    result[label] = { url, thumbnail_url: thumbnail };
    count += 1;
  }
  return count > 0 ? result : null;
}

export type MediaStatus = "same" | "moved" | "added" | "removed";

export interface MediaMark {
  label: string;
  status: MediaStatus;
}

/** The labels of a picture change value, or null when it is not a picture value. */
export function mediaLabels(
  item: ChangeItemV1,
  value: ChangeValue,
): string[] | null {
  if (!MEDIA_FIELDS.has(item.field)) return null;
  if (value.type === "text_list") return value.value;
  if (value.type === "text") return value.value === null ? [] : [value.value];
  return null;
}

/** True when the change is a picture change and at least one label has a thumbnail. */
export function hasMediaThumbnails(
  item: ChangeItemV1,
  media: ChangeMedia | null | undefined,
): boolean {
  if (!media) return false;
  const labels = [
    ...(mediaLabels(item, item.before) ?? []),
    ...(mediaLabels(item, item.after) ?? []),
  ];
  return labels.some((label) => Object.hasOwn(media, label));
}

/** Above this many label pairs the order comparison is skipped (membership only). */
const MAX_CELLS = 250_000;

/**
 * Marks each picture of both sides: those that keep their place in the shared order are "same",
 * those present on both sides but out of that order are "moved", the rest "removed" (before only)
 * or "added" (after only).
 */
export function markMediaLists(
  before: string[],
  after: string[],
): { before: MediaMark[]; after: MediaMark[] } {
  const n = before.length;
  const m = after.length;
  const keptBefore = new Set<number>();
  const keptAfter = new Set<number>();
  if (n * m <= MAX_CELLS) {
    const table = Array.from({ length: n + 1 }, () =>
      new Array<number>(m + 1).fill(0),
    );
    for (let i = n - 1; i >= 0; i -= 1)
      for (let j = m - 1; j >= 0; j -= 1)
        table[i][j] =
          before[i] === after[j]
            ? table[i + 1][j + 1] + 1
            : Math.max(table[i + 1][j], table[i][j + 1]);
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
      if (before[i] === after[j]) {
        keptBefore.add(i);
        keptAfter.add(j);
        i += 1;
        j += 1;
      } else if (table[i + 1][j] >= table[i][j + 1]) i += 1;
      else j += 1;
    }
  } else {
    before.forEach((label, index) => {
      if (after.includes(label)) keptBefore.add(index);
    });
    after.forEach((label, index) => {
      if (before.includes(label)) keptAfter.add(index);
    });
  }
  const afterSet = new Set(after);
  const beforeSet = new Set(before);
  return {
    before: before.map((label, index) => ({
      label,
      status: keptBefore.has(index)
        ? "same"
        : afterSet.has(label)
          ? "moved"
          : "removed",
    })),
    after: after.map((label, index) => ({
      label,
      status: keptAfter.has(index)
        ? "same"
        : beforeSet.has(label)
          ? "moved"
          : "added",
    })),
  };
}

export interface LightboxPicture {
  label: string;
  url: string;
}

/**
 * The pictures the viewer steps through for one picture change: the after list first, then the
 * pictures that were removed. Each label once; labels without a full-size URL are left out.
 */
export function lightboxSequence(
  before: MediaMark[],
  after: MediaMark[],
  media: ChangeMedia,
): LightboxPicture[] {
  const seen = new Set<string>();
  const result: LightboxPicture[] = [];
  const candidates = [
    ...after,
    ...before.filter((mark) => mark.status === "removed"),
  ];
  for (const { label } of candidates) {
    if (seen.has(label)) continue;
    seen.add(label);
    const entry = Object.hasOwn(media, label) ? media[label] : undefined;
    if (entry?.url) result.push({ label, url: entry.url });
  }
  return result;
}
