/**
 * The Trash filter of the project and yarn lists: empty is the normal list (trashed items are
 * left out by the server), `only` lists nothing but trashed items. It is the `trashed` URL
 * parameter of `/app/projects` and `/app/yarns` and the `trashed` query parameter of the API.
 */
export type TrashChoice = "" | "only";

export function trashChoiceFromParam(value: string | null): TrashChoice {
  return value?.trim() === "only" ? "only" : "";
}

/** What a trashed item adds to a list entry; both absent for an item that is not in the trash. */
export interface TrashFields {
  trashed_at?: string;
  purge_at?: string;
}

/** Spread into a parsed summary: only the valid timestamps the server sent. */
export function trashField(value: unknown): TrashFields {
  if (typeof value !== "object" || value === null) return {};
  const record = value as Record<string, unknown>;
  const fields: TrashFields = {};
  if (
    typeof record.trashed_at === "string" &&
    !Number.isNaN(Date.parse(record.trashed_at))
  )
    fields.trashed_at = record.trashed_at;
  if (
    typeof record.purge_at === "string" &&
    !Number.isNaN(Date.parse(record.purge_at))
  )
    fields.purge_at = record.purge_at;
  return fields;
}

/**
 * Whole days until the trash is emptied for this item, at least 1 while it is still there; null
 * when it is not trashed or the server sent no purge time.
 */
export function trashDaysLeft(
  item: TrashFields,
  now: number = Date.now(),
): number | null {
  if (!item.trashed_at || !item.purge_at) return null;
  const left = Date.parse(item.purge_at) - now;
  return Math.max(1, Math.ceil(left / 86_400_000));
}
