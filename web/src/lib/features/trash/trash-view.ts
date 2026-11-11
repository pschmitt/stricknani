import type { TrashItem, TrashScope } from "./trash-api";

/** Scope to request: only administrators can ask for everyone's items. */
export function effectiveTrashScope(
  isAdmin: boolean,
  chosen: "mine" | "all",
): TrashScope {
  return isAdmin ? chosen : "mine";
}

/** Whether the owner is worth showing: administrators looking at other people's items. */
export function showsOwner(scope: TrashScope, item: TrashItem): boolean {
  return scope === "all" && item.owner !== null;
}

/** Newest deletion first; ties keep the server order. */
export function sortTrash(items: readonly TrashItem[]): TrashItem[] {
  return items
    .map((item, index) => ({ item, index }))
    .sort((left, right) => {
      const a = Date.parse(left.item.deleted_at);
      const b = Date.parse(right.item.deleted_at);
      const byTime = (Number.isNaN(b) ? 0 : b) - (Number.isNaN(a) ? 0 : a);
      return byTime !== 0 ? byTime : left.index - right.index;
    })
    .map(({ item }) => item);
}

export function trashItemKey(item: Pick<TrashItem, "kind" | "id">): string {
  return `${item.kind}:${item.id}`;
}
