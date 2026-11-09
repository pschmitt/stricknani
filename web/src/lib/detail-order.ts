/**
 * The per-user order of the reorderable sections on the project and yarn detail
 * pages. The ids and default orders mirror Android's `ProjectDetailCard` /
 * `YarnDetailCard` (`DetailCardOrder.kt`) and the server's `detail_order.rs`.
 * Attachments sit fixed under the gallery and the source links are rows of the
 * details section, so they are not reorderable.
 */

export type DetailCardDomain = "project" | "yarn";

export interface DetailCardOrders {
  project: string[];
  yarn: string[];
}

export const PROJECT_DETAIL_CARDS = [
  "details",
  "materials",
  "description",
  "steps",
] as const;

export const YARN_DETAIL_CARDS = [
  "details",
  "description",
  "notes",
  "used_in",
] as const;

export function defaultDetailOrder(domain: DetailCardDomain): string[] {
  return [...(domain === "project" ? PROJECT_DETAIL_CARDS : YARN_DETAIL_CARDS)];
}

/** Drops unknown and duplicate ids and appends missing sections in default order. */
export function sanitizeDetailOrder(
  domain: DetailCardDomain,
  saved: readonly string[] | null | undefined,
): string[] {
  const defaults = defaultDetailOrder(domain);
  const order: string[] = [];
  for (const id of saved ?? []) {
    if (defaults.includes(id) && !order.includes(id)) order.push(id);
  }
  for (const id of defaults) if (!order.includes(id)) order.push(id);
  return order;
}

/** The saved order restricted to the sections currently shown. */
export function visibleDetailOrder(
  domain: DetailCardDomain,
  saved: readonly string[] | null | undefined,
  available: readonly string[],
): string[] {
  const ordered = sanitizeDetailOrder(domain, saved);
  return [
    ...ordered.filter((id) => available.includes(id)),
    ...available.filter((id) => !ordered.includes(id)),
  ];
}

/** Moves one entry; an out-of-range or no-op move returns the list unchanged. */
export function moveDetailCard(
  order: readonly string[],
  fromIndex: number,
  toIndex: number,
): string[] {
  const inRange = (index: number) => index >= 0 && index < order.length;
  if (!inRange(fromIndex) || !inRange(toIndex) || fromIndex === toIndex)
    return [...order];
  const next = [...order];
  const [moved] = next.splice(fromIndex, 1);
  next.splice(toIndex, 0, moved);
  return next;
}

/** Whether the order differs from the default one. */
export function isDefaultDetailOrder(
  domain: DetailCardDomain,
  order: readonly string[],
): boolean {
  const defaults = defaultDetailOrder(domain);
  const clean = sanitizeDetailOrder(domain, order);
  return clean.every((id, index) => id === defaults[index]);
}

/** Parses the server's `detail_card_order`; anything malformed is the default. */
export function parseDetailOrders(value: unknown): DetailCardOrders {
  const record =
    typeof value === "object" && value !== null
      ? (value as Record<string, unknown>)
      : {};
  const list = (entry: unknown): string[] =>
    Array.isArray(entry)
      ? entry.filter((id): id is string => typeof id === "string")
      : [];
  return {
    project: sanitizeDetailOrder("project", list(record.project)),
    yarn: sanitizeDetailOrder("yarn", list(record.yarn)),
  };
}
