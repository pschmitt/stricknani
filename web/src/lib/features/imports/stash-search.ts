import { listYarns, type YarnPage } from "../yarns/yarn-api.ts";

/**
 * Search the importing user's own yarns. An import always creates a project owned by the
 * importing user, and a project can only link yarns of its owner, so the stash is never wider
 * than `scope=mine`: administrators would otherwise be offered everyone's yarns.
 */
export function searchOwnStash(
  query: string,
  signal: AbortSignal,
): Promise<YarnPage> {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  return listYarns(params.toString(), signal, { scope: "mine" });
}
