import type { ProjectSummary } from "#lib/features/projects/project-api.ts";

/** The carousel shows at most this many projects; "See all" opens the rest. */
export const CONTINUE_LIMIT = 10;

/**
 * The projects the "Continue knitting" carousel shows: most recently updated first, at most
 * [CONTINUE_LIMIT]. The sort is stable, so projects with the same time keep the server order.
 */
export function continueProjects(projects: ProjectSummary[]): ProjectSummary[] {
  const stamp = (project: ProjectSummary) => {
    const value = Date.parse(project.updated_at);
    return Number.isNaN(value) ? 0 : value;
  };
  return projects
    .map((project, index) => ({ project, index, time: stamp(project) }))
    .sort((a, b) => b.time - a.time || a.index - b.index)
    .slice(0, CONTINUE_LIMIT)
    .map(({ project }) => project);
}

/**
 * Which card is showing: the one whose left edge is nearest the scroll position. At the far end
 * the last card can not reach the left edge, so it counts as showing.
 */
export function activeSlide(
  offsets: number[],
  scrollLeft: number,
  viewport: number,
  scrollWidth: number,
): number {
  if (offsets.length === 0) return 0;
  if (scrollLeft + viewport >= scrollWidth - 2 && scrollLeft > 0)
    return offsets.length - 1;
  let best = 0;
  for (let index = 1; index < offsets.length; index += 1) {
    if (
      Math.abs(offsets[index] - scrollLeft) <
      Math.abs(offsets[best] - scrollLeft)
    )
      best = index;
  }
  return best;
}

/** The card an arrow key moves to from [from], or null when the key does not move. */
export function slideForKey(
  key: string,
  from: number,
  count: number,
  rtl = false,
): number | null {
  const last = count - 1;
  if (last < 0) return null;
  const forward = rtl ? "ArrowLeft" : "ArrowRight";
  const backward = rtl ? "ArrowRight" : "ArrowLeft";
  if (key === forward) return Math.min(from + 1, last);
  if (key === backward) return Math.max(from - 1, 0);
  if (key === "Home") return 0;
  if (key === "End") return last;
  return null;
}
