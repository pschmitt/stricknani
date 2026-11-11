/**
 * Photo and file changes of an editor that wait for Save: removals and the order of a photo group.
 * Nothing reaches the server before the form is saved; leaving without saving drops all of it.
 */

export interface StagedMedia {
  /** Photos (project images or yarn photos) taken out of the form. */
  removedImageIds: number[];
  /** Attachments taken out of the form. */
  removedAttachmentIds: number[];
  /** Full order of a photo group, by group key, for groups the user reordered. */
  order: Record<string, number[]>;
}

export interface GroupedImage {
  id: number;
  step_id?: number | null;
}

export const TITLE_GROUP = "title";
export const YARN_GROUP = "photos";
export const stepGroup = (stepId: number): string => `step:${stepId}`;

export function groupKeyOf(image: GroupedImage): string {
  return image.step_id == null ? TITLE_GROUP : stepGroup(image.step_id);
}

export function emptyStaged(): StagedMedia {
  return { removedImageIds: [], removedAttachmentIds: [], order: {} };
}

/** Whether anything was staged at all, even a move that put a photo back where it was. */
export function hasStagedMedia(staged: StagedMedia): boolean {
  return (
    staged.removedImageIds.length > 0 ||
    staged.removedAttachmentIds.length > 0 ||
    Object.keys(staged.order).length > 0
  );
}

/** How many photos and files the next save moves to the trash. */
export function stagedRemovalCount(staged: StagedMedia): number {
  return staged.removedImageIds.length + staged.removedAttachmentIds.length;
}

/** The photos as the form shows them: removed ones hidden, reordered groups in their new order. */
export function applyStagedImages<T extends GroupedImage>(
  images: readonly T[],
  staged: StagedMedia,
  groupOf: (image: T) => string = groupKeyOf,
): T[] {
  const removed = new Set(staged.removedImageIds);
  const visible = images.filter((image) => !removed.has(image.id));
  const orderedGroups = new Map<string, T[]>();
  for (const [key, ids] of Object.entries(staged.order)) {
    const members = visible.filter((image) => groupOf(image) === key);
    const rank = new Map(ids.map((id, index) => [id, index]));
    orderedGroups.set(
      key,
      [...members].sort(
        (a, b) =>
          (rank.get(a.id) ?? Number.MAX_SAFE_INTEGER) -
          (rank.get(b.id) ?? Number.MAX_SAFE_INTEGER),
      ),
    );
  }
  const taken = new Map<string, number>();
  return visible.map((image) => {
    const key = groupOf(image);
    const ordered = orderedGroups.get(key);
    if (!ordered) return image;
    const index = taken.get(key) ?? 0;
    taken.set(key, index + 1);
    return ordered[index] ?? image;
  });
}

/** Stage a move inside one group; `group` is the group as it is shown now. */
export function stageMove(
  staged: StagedMedia,
  groupKey: string,
  group: readonly { id: number }[],
  from: number,
  to: number,
): StagedMedia {
  if (
    !Number.isInteger(from) ||
    !Number.isInteger(to) ||
    from === to ||
    from < 0 ||
    to < 0 ||
    from >= group.length ||
    to >= group.length
  )
    return staged;
  const ids = group.map((item) => item.id);
  const [moved] = ids.splice(from, 1);
  ids.splice(to, 0, moved);
  return { ...staged, order: { ...staged.order, [groupKey]: ids } };
}

export function stageImageRemoval(
  staged: StagedMedia,
  imageId: number,
): StagedMedia {
  if (staged.removedImageIds.includes(imageId)) return staged;
  return {
    ...staged,
    removedImageIds: [...staged.removedImageIds, imageId],
  };
}

export function stageAttachmentRemoval(
  staged: StagedMedia,
  attachmentId: number,
): StagedMedia {
  if (staged.removedAttachmentIds.includes(attachmentId)) return staged;
  return {
    ...staged,
    removedAttachmentIds: [...staged.removedAttachmentIds, attachmentId],
  };
}

export function unstageImageRemoval(
  staged: StagedMedia,
  imageId: number,
): StagedMedia {
  return {
    ...staged,
    removedImageIds: staged.removedImageIds.filter((id) => id !== imageId),
  };
}

export function unstageAttachmentRemoval(
  staged: StagedMedia,
  attachmentId: number,
): StagedMedia {
  return {
    ...staged,
    removedAttachmentIds: staged.removedAttachmentIds.filter(
      (id) => id !== attachmentId,
    ),
  };
}

export interface MediaSavePlan {
  deleteImageIds: number[];
  deleteAttachmentIds: number[];
  /** Orders to send after the deletions, one per changed group, without the removed photos. */
  orders: { group: string; ids: number[] }[];
}

/**
 * What a save must do for the staged changes, against the photos as the server has them. Removed
 * photos are deleted first, so an order lists only the photos that stay. A group whose order ends
 * up the same as on the server, or whose photos are gone, sends nothing. Photos of a step the
 * save removes (`goneGroups`) go to the trash with it and need neither.
 */
export function planMediaSave(
  serverImages: readonly GroupedImage[],
  serverAttachmentIds: readonly number[],
  staged: StagedMedia,
  goneGroups: ReadonlySet<string> = new Set(),
  groupOf: (image: GroupedImage) => string = groupKeyOf,
): MediaSavePlan {
  const present = new Set(serverImages.map((image) => image.id));
  const inGone = (image: GroupedImage) => goneGroups.has(groupOf(image));
  const deleteImageIds = staged.removedImageIds.filter(
    (id) =>
      present.has(id) &&
      !inGone(serverImages.find((image) => image.id === id) as GroupedImage),
  );
  const removed = new Set(staged.removedImageIds);
  const orders: MediaSavePlan["orders"] = [];
  for (const [group, ids] of Object.entries(staged.order)) {
    if (goneGroups.has(group)) continue;
    const members = serverImages.filter(
      (image) => groupOf(image) === group && !removed.has(image.id),
    );
    const rank = new Map(ids.map((id, index) => [id, index]));
    const next = members
      .map((image) => image.id)
      .sort(
        (a, b) =>
          (rank.get(a) ?? Number.MAX_SAFE_INTEGER) -
          (rank.get(b) ?? Number.MAX_SAFE_INTEGER),
      );
    const same = next.every((id, index) => id === members[index]?.id);
    if (members.length > 1 && !same) orders.push({ group, ids: next });
  }
  return {
    deleteImageIds,
    deleteAttachmentIds: staged.removedAttachmentIds.filter((id) =>
      serverAttachmentIds.includes(id),
    ),
    orders,
  };
}

/**
 * Whether Save has something to do for the staged changes: a removal, or an order that differs
 * from the server's. A photo moved away and back leaves the form clean.
 */
export function hasPendingMedia(
  serverImages: readonly GroupedImage[],
  staged: StagedMedia,
  groupOf: (image: GroupedImage) => string = groupKeyOf,
): boolean {
  return (
    stagedRemovalCount(staged) > 0 ||
    planMediaSave(serverImages, [], staged, new Set(), groupOf).orders.length >
      0
  );
}
