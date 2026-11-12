import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";

export interface OrderedItem {
  id: number;
}

export interface ReorderResult {
  ids: number[];
  updated_at: string;
}

/** Return a copy of items with the entry at from moved to index to. */
export function moveItem<T>(
  items: readonly T[],
  from: number,
  to: number,
): T[] {
  const next = [...items];
  if (
    !Number.isInteger(from) ||
    !Number.isInteger(to) ||
    from < 0 ||
    to < 0 ||
    from >= next.length ||
    to >= next.length ||
    from === to
  )
    return next;
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return next;
}

export function idsOf(items: readonly OrderedItem[]): number[] {
  return items.map((item) => item.id);
}

export function sameOrder(
  a: readonly OrderedItem[],
  b: readonly OrderedItem[],
): boolean {
  return a.length === b.length && a.every((item, i) => item.id === b[i].id);
}

/** Clamp a keyboard move (delta of -1 or 1) to the list bounds. */
export function keyboardTarget(
  index: number,
  delta: number,
  length: number,
): number {
  return Math.min(Math.max(index + delta, 0), length - 1);
}

/** Map an arrow key to a move delta; null for every other key. */
export function deltaForKey(key: string, rtl = false): number | null {
  if (key === "ArrowUp") return -1;
  if (key === "ArrowDown") return 1;
  if (key === "ArrowLeft") return rtl ? 1 : -1;
  if (key === "ArrowRight") return rtl ? -1 : 1;
  return null;
}

/**
 * Pick the tile whose centre is nearest to the pointer. Works for lists and
 * wrapping grids because it does not assume a single axis.
 */
export function nearestIndex(
  centers: readonly { x: number; y: number }[],
  x: number,
  y: number,
): number {
  let best = -1;
  let bestDistance = Infinity;
  centers.forEach((center, index) => {
    const distance = (center.x - x) ** 2 + (center.y - y) ** 2;
    if (distance < bestDistance) {
      bestDistance = distance;
      best = index;
    }
  });
  return best;
}

/**
 * Write a reordered group back into a flat list: the group items fill the slots
 * the group occupied before, so items of other groups keep their positions.
 */
export function replaceGroup<T extends OrderedItem>(
  all: readonly T[],
  group: readonly T[],
  reordered: readonly T[],
): T[] {
  const members = new Set(group.map((item) => item.id));
  const queue = [...reordered];
  return all.map((item) => (members.has(item.id) ? queue.shift()! : item));
}

export interface ReorderOptions<T extends OrderedItem> {
  items: readonly T[];
  from: number;
  to: number;
  setItems: (items: T[]) => void;
  send: (ids: number[]) => Promise<ReorderResult>;
  /** Re-fetch authoritative state; called after a 422. */
  refetch: () => Promise<void>;
  onError: (error: unknown) => void;
}

/**
 * Optimistically apply a move, send the new order, and revert on failure.
 * A 422 means the group changed on the server, so the caller re-fetches.
 * Returns true when the server accepted the order.
 */
export async function applyOptimisticReorder<T extends OrderedItem>(
  options: ReorderOptions<T>,
): Promise<boolean> {
  const original = [...options.items];
  const next = moveItem(original, options.from, options.to);
  if (sameOrder(original, next)) return false;
  options.setItems(next);
  try {
    await options.send(idsOf(next));
    return true;
  } catch (error) {
    options.setItems(original);
    if (error instanceof ApiError && error.status === 422) {
      try {
        await options.refetch();
      } catch {
        // the revert above already restored the previous order
      }
    }
    options.onError(error);
    return false;
  }
}

export async function putOrder(
  path: string,
  ids: number[],
  session: SessionData,
  signal?: AbortSignal,
): Promise<ReorderResult> {
  const payload: unknown = await apiRequest(path, {
    method: "PUT",
    csrfToken: session.csrf_token,
    body: { ids },
    signal,
  });
  const data =
    typeof payload === "object" && payload !== null
      ? (payload as { data?: unknown }).data
      : undefined;
  if (
    typeof data !== "object" ||
    data === null ||
    !Array.isArray((data as ReorderResult).ids) ||
    typeof (data as ReorderResult).updated_at !== "string"
  )
    throw new ApiError("Unexpected server response.", 502, "invalid_response");
  return data as ReorderResult;
}

export const projectTitleOrderPath = (projectId: number): string =>
  `/api/v2/projects/${projectId}/images/order`;
export const projectStepOrderPath = (
  projectId: number,
  stepId: number,
): string => `/api/v2/projects/${projectId}/steps/${stepId}/images/order`;
export const yarnPhotoOrderPath = (yarnId: number): string =>
  `/api/v2/yarns/${yarnId}/photos/order`;

/** A photo can move to the first position (the cover) when it is not first yet. */
export function canMoveToFirst(index: number, length: number): boolean {
  return Number.isInteger(index) && index > 0 && index < length;
}

/** Press time in ms before a tile enters reorder mode or lifts (Android: long press). */
export const LONG_PRESS_MS = 400;
/** Pointer travel in px that cancels a pending long press. */
export const MOVE_SLOP = 8;
/** Pointer travel in px that lifts a tile at once with a mouse in reorder mode. */
export const MOUSE_DRAG_SLOP = 4;

/** Order items by a list of ids; ids that are unknown are skipped, missing items are appended. */
export function orderByIds<T extends OrderedItem>(
  items: readonly T[],
  ids: readonly number[],
): T[] {
  const byId = new Map(items.map((item) => [item.id, item]));
  const ordered = ids.flatMap((id) => {
    const item = byId.get(id);
    return item ? [item] : [];
  });
  const seen = new Set(ordered.map((item) => item.id));
  return [...ordered, ...items.filter((item) => !seen.has(item.id))];
}

/** How many columns a grid has, from the top offsets of its tiles in order. */
export function gridColumns(tops: readonly number[]): number {
  if (tops.length === 0) return 1;
  const first = tops[0] as number;
  const end = tops.findIndex((top) => Math.abs(top - first) > 1);
  return end === -1 ? tops.length : Math.max(end, 1);
}

export interface ReorderKey {
  key: string;
  altKey?: boolean;
  ctrlKey?: boolean;
  metaKey?: boolean;
}

/**
 * The index a focused tile moves to for an arrow key: left and right move by one
 * (mirrored in right-to-left), up and down by one row of the grid. Clamped to
 * the list; null for any other key or a modified key.
 */
export function keyboardMoveTarget(
  event: ReorderKey,
  index: number,
  length: number,
  columns: number,
  rtl = false,
): number | null {
  if (event.altKey || event.ctrlKey || event.metaKey) return null;
  const rows = Math.max(columns, 1);
  let delta: number | null;
  if (event.key === "ArrowUp") delta = -rows;
  else if (event.key === "ArrowDown") delta = rows;
  else delta = deltaForKey(event.key, rtl);
  if (delta === null) return null;
  return keyboardTarget(index, delta, length);
}

/**
 * Pixels to scroll per frame while a dragged tile is near the top or bottom edge
 * of the scroll area: zero in the middle, growing to maxSpeed at the edge.
 */
export function edgeScrollDelta(
  pointerY: number,
  top: number,
  bottom: number,
  zone = 72,
  maxSpeed = 18,
): number {
  if (pointerY < top + zone) {
    const depth = Math.min((top + zone - pointerY) / zone, 1);
    return -Math.ceil(depth * maxSpeed);
  }
  if (pointerY > bottom - zone) {
    const depth = Math.min((pointerY - (bottom - zone)) / zone, 1);
    return Math.ceil(depth * maxSpeed);
  }
  return 0;
}
