/** The fields of a project image that decide which group it belongs to. */
export interface GroupableImage {
  step_id: number | null;
}

export interface ProjectImageGroups<T extends GroupableImage> {
  /** The project gallery: every image without a step, in the server order. The first is the cover. */
  gallery: T[];
  /** Images whose step no longer exists. */
  orphans: T[];
}

/**
 * Split project images into the project gallery and orphans. `is_title_image` only marks the
 * cover, so it must not decide who belongs to the gallery: all of them can be reordered together.
 */
export function groupProjectImages<T extends GroupableImage>(
  images: readonly T[],
  stepIds: readonly number[],
): ProjectImageGroups<T> {
  const steps = new Set(stepIds);
  return {
    gallery: images.filter((image) => image.step_id === null),
    orphans: images.filter(
      (image) => image.step_id !== null && !steps.has(image.step_id),
    ),
  };
}
