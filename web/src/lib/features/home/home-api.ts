import {
  listProjects,
  type ProjectSummary,
} from "#lib/features/projects/project-api.ts";
import { continueProjects } from "./continue-carousel";
import type { LibraryScope } from "#lib/library-scope.svelte.ts";
import { listYarns, type YarnSummary } from "#lib/features/yarns/yarn-api.ts";

export const HOME_PREVIEW_LIMIT = 6;

export async function loadFavoriteProjects(
  signal: AbortSignal,
  scope?: LibraryScope,
): Promise<ProjectSummary[]> {
  const page = await listProjects("favorite=true", signal, { scope });
  return page.data.slice(0, HOME_PREVIEW_LIMIT);
}

export async function loadFavoriteYarns(
  signal: AbortSignal,
  scope?: LibraryScope,
): Promise<YarnSummary[]> {
  const page = await listYarns("favorite=true", signal, { scope });
  return page.data.slice(0, HOME_PREVIEW_LIMIT);
}

export async function loadRecentProjects(
  signal: AbortSignal,
  scope?: LibraryScope,
): Promise<ProjectSummary[]> {
  const page = await listProjects("", signal, { scope });
  return page.data.slice(0, HOME_PREVIEW_LIMIT);
}

/**
 * Projects the user owns or can access through an explicit share for "Continue knitting": most
 * recently updated first, at most [CONTINUE_LIMIT]. Always use the mine scope, even for admins,
 * so the home carousel cannot include unrelated projects visible only through admin access.
 */
export async function loadOngoingProjects(
  signal: AbortSignal,
  _scope?: LibraryScope,
): Promise<ProjectSummary[]> {
  const page = await listProjects("status=in_progress", signal, {
    // The mine scope includes owned and explicitly shared projects. The caller's global scope may
    // be `all` for an administrator, which would also include unrelated admin-visible projects.
    scope: "mine",
  });
  return continueProjects(page.data);
}
