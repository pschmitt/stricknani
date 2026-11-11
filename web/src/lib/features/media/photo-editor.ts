import { apiRequest } from "#lib/api.ts";

/** Clockwise rotation in degrees, applied before the crop. */
export type Rotation = 0 | 90 | 180 | 270;

/** Crop box in fractions (0..1) of the rotated, upright image. */
export interface CropBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

export type CropHandle = "nw" | "ne" | "sw" | "se";

export interface EditBody {
  rotate: Rotation;
  crop?: CropBox;
}

export interface Size {
  width: number;
  height: number;
}

/** The server rejects results smaller than this many pixels per side. */
export const MIN_RESULT_PX = 16;
/** The crop box never shrinks below this fraction of a side in the UI. */
export const MIN_CROP_FRACTION = 0.08;
/** Fractions closer than this to a full-image edge count as the edge. */
const FULL_EPSILON = 0.0005;
const FRACTION_DIGITS = 6;

export const FULL_CROP: CropBox = { x: 0, y: 0, width: 1, height: 1 };

export function fullCrop(): CropBox {
  return { ...FULL_CROP };
}

export function normalizeRotation(degrees: number): Rotation {
  const value = (((Math.round(degrees / 90) * 90) % 360) + 360) % 360;
  return value as Rotation;
}

/** Turn by a quarter step; positive is clockwise. */
export function rotateBy(rotation: Rotation, quarterTurns: number): Rotation {
  return normalizeRotation(rotation + quarterTurns * 90);
}

/**
 * Carry a crop box through a quarter-turn rotation of the image, so it frames the same picture
 * content afterwards. Positive is clockwise: (x, y, w, h) becomes (1 - y - h, x, h, w); a
 * counter-clockwise turn is (y, 1 - x - w, h, w). The result is clamped to the image.
 */
export function rotateCrop(crop: CropBox, quarterTurns: number): CropBox {
  const turns = ((Math.round(quarterTurns) % 4) + 4) % 4;
  let { x, y, width, height } = crop;
  for (let i = 0; i < turns; i++) {
    [x, y, width, height] = [1 - y - height, x, height, width];
  }
  width = clamp(width, 0, 1);
  height = clamp(height, 0, 1);
  return {
    x: clamp(x, 0, 1 - width),
    y: clamp(y, 0, 1 - height),
    width,
    height,
  };
}

/** Size of the image after the rotation is applied. */
export function rotatedSize(natural: Size, rotation: Rotation): Size {
  return rotation === 90 || rotation === 270
    ? { width: natural.height, height: natural.width }
    : { ...natural };
}

/** Largest size with the given aspect that fits into the box, never upscaled past `maxScale`. */
export function fitSize(content: Size, box: Size, maxScale = 4): Size {
  if (
    content.width <= 0 ||
    content.height <= 0 ||
    box.width <= 0 ||
    box.height <= 0
  )
    return { width: 0, height: 0 };
  const scale = Math.min(
    box.width / content.width,
    box.height / content.height,
    maxScale,
  );
  return { width: content.width * scale, height: content.height * scale };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/** Minimum crop size as fractions: at least 16 px of the result, never below the UI minimum. */
export function minCropFractions(rotated: Size): Size {
  const forSide = (side: number): number =>
    side > 0
      ? Math.min(1, Math.max(MIN_CROP_FRACTION, MIN_RESULT_PX / side))
      : 1;
  return { width: forSide(rotated.width), height: forSide(rotated.height) };
}

/** Pointer position as fractions of the preview rectangle, clamped to the image. */
export function pointToFraction(
  clientX: number,
  clientY: number,
  rect: { left: number; top: number; width: number; height: number },
): { x: number; y: number } {
  if (rect.width <= 0 || rect.height <= 0) return { x: 0, y: 0 };
  return {
    x: clamp((clientX - rect.left) / rect.width, 0, 1),
    y: clamp((clientY - rect.top) / rect.height, 0, 1),
  };
}

/** Keep a crop box inside the image and above the minimum size. */
export function clampCrop(crop: CropBox, min: Size): CropBox {
  const width = clamp(crop.width, Math.min(min.width, 1), 1);
  const height = clamp(crop.height, Math.min(min.height, 1), 1);
  return {
    x: clamp(crop.x, 0, 1 - width),
    y: clamp(crop.y, 0, 1 - height),
    width,
    height,
  };
}

/** Drag one corner to a point; the opposite corner stays where it is. */
export function resizeCrop(
  crop: CropBox,
  handle: CropHandle,
  point: { x: number; y: number },
  min: Size,
): CropBox {
  const right = crop.x + crop.width;
  const bottom = crop.y + crop.height;
  let left = crop.x;
  let top = crop.y;
  let nextRight = right;
  let nextBottom = bottom;
  if (handle === "nw" || handle === "sw")
    left = clamp(point.x, 0, right - min.width);
  else nextRight = clamp(point.x, crop.x + min.width, 1);
  if (handle === "nw" || handle === "ne")
    top = clamp(point.y, 0, bottom - min.height);
  else nextBottom = clamp(point.y, crop.y + min.height, 1);
  return {
    x: left,
    y: top,
    width: nextRight - left,
    height: nextBottom - top,
  };
}

/** Move the whole box by a fraction delta, keeping it inside the image. */
export function moveCrop(crop: CropBox, dx: number, dy: number): CropBox {
  return {
    ...crop,
    x: clamp(crop.x + dx, 0, 1 - crop.width),
    y: clamp(crop.y + dy, 0, 1 - crop.height),
  };
}

/** The point of a corner handle, in fractions. */
export function handlePoint(
  crop: CropBox,
  handle: CropHandle,
): { x: number; y: number } {
  return {
    x: handle === "nw" || handle === "sw" ? crop.x : crop.x + crop.width,
    y: handle === "nw" || handle === "ne" ? crop.y : crop.y + crop.height,
  };
}

export const NUDGE_STEP = 0.01;
export const NUDGE_STEP_LARGE = 0.05;

/** Direction of an arrow key, or null for other keys. */
export function arrowDelta(key: string): { dx: number; dy: number } | null {
  switch (key) {
    case "ArrowLeft":
      return { dx: -1, dy: 0 };
    case "ArrowRight":
      return { dx: 1, dy: 0 };
    case "ArrowUp":
      return { dx: 0, dy: -1 };
    case "ArrowDown":
      return { dx: 0, dy: 1 };
    default:
      return null;
  }
}

/** Nudge a corner handle with an arrow key. */
export function nudgeHandle(
  crop: CropBox,
  handle: CropHandle,
  key: string,
  min: Size,
  large = false,
): CropBox {
  const delta = arrowDelta(key);
  if (!delta) return crop;
  const step = large ? NUDGE_STEP_LARGE : NUDGE_STEP;
  const point = handlePoint(crop, handle);
  return resizeCrop(
    crop,
    handle,
    { x: point.x + delta.dx * step, y: point.y + delta.dy * step },
    min,
  );
}

/** Nudge the whole box with an arrow key. */
export function nudgeBox(crop: CropBox, key: string, large = false): CropBox {
  const delta = arrowDelta(key);
  if (!delta) return crop;
  const step = large ? NUDGE_STEP_LARGE : NUDGE_STEP;
  return moveCrop(crop, delta.dx * step, delta.dy * step);
}

export function isFullCrop(crop: CropBox): boolean {
  return (
    crop.x <= FULL_EPSILON &&
    crop.y <= FULL_EPSILON &&
    crop.x + crop.width >= 1 - FULL_EPSILON &&
    crop.y + crop.height >= 1 - FULL_EPSILON
  );
}

/** True when saving would change nothing. */
export function isUnchanged(rotation: Rotation, crop: CropBox): boolean {
  return rotation === 0 && isFullCrop(crop);
}

function round(value: number): number {
  const factor = 10 ** FRACTION_DIGITS;
  return Math.round(value * factor) / factor;
}

/** Request body: `crop` is left out when it covers the whole image. */
export function buildEditBody(rotation: Rotation, crop: CropBox): EditBody {
  if (isFullCrop(crop)) return { rotate: rotation };
  const x = round(clamp(crop.x, 0, 1));
  const y = round(clamp(crop.y, 0, 1));
  const width = round(clamp(crop.width, 0, 1 - x));
  const height = round(clamp(crop.height, 0, 1 - y));
  return { rotate: rotation, crop: { x, y, width, height } };
}

export function projectImageEditPath(
  projectId: number,
  imageId: number,
): string {
  return `/api/v2/projects/${projectId}/images/${imageId}/edit`;
}

export function yarnPhotoEditPath(yarnId: number, photoId: number): string {
  return `/api/v2/yarns/${yarnId}/photos/${photoId}/edit`;
}

/** Apply the edit on the server; the photo id stays, its URLs change. */
export async function editPhoto(
  path: string,
  body: EditBody,
  csrfToken: string,
  signal?: AbortSignal,
): Promise<void> {
  await apiRequest(path, { method: "POST", csrfToken, body, signal });
}

/** The description the field starts from and the server stores, compared trimmed. */
export function normalizeAltText(value: string): string {
  return value.trim();
}

/** The description changed (an empty field on a stored description counts as a change). */
export function altTextChanged(initial: string, current: string): boolean {
  return normalizeAltText(initial) !== normalizeAltText(current);
}

/** Longest description the field accepts (the server allows 4096 bytes; 4 bytes per char). */
export const MAX_ALT_TEXT_LENGTH = 1000;

/** The photo's own route, `…/edit` without its last segment. */
export function altTextPath(editPath: string): string {
  return editPath.replace(/\/edit$/, "");
}

/**
 * Change the description of a photo; empty falls back to the file name on the server. Resolves
 * with the description the server stored.
 */
export async function updateAltText(
  editPath: string,
  altText: string,
  csrfToken: string,
  signal?: AbortSignal,
): Promise<string> {
  const payload: unknown = await apiRequest(altTextPath(editPath), {
    method: "PATCH",
    csrfToken,
    body: { alt_text: normalizeAltText(altText) },
    signal,
  });
  const stored =
    typeof payload === "object" && payload !== null && "data" in payload
      ? (payload.data as { alt_text?: unknown } | null)?.alt_text
      : undefined;
  return typeof stored === "string" ? stored : normalizeAltText(altText);
}
