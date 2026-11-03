import { hasPendingApiMutations } from "#lib/api.ts";

export type UpdateReadiness = { dirty: boolean; busy: boolean };
export type TabReadiness = "ready" | "dirty" | "busy";

type UpdateGuard = () => UpdateReadiness;

const guards = new Set<UpdateGuard>();

/**
 * Forms register whether they hold unsaved edits or run an action. A tab that
 * is dirty or busy is never reloaded into a new app version on its own; it
 * keeps the version it has until it navigates cleanly or the user reloads.
 */
export function registerPwaUpdateGuard(guard: UpdateGuard): () => void {
  guards.add(guard);
  return () => guards.delete(guard);
}

/** Whether this tab can be reloaded without losing anything. */
export function updateReadiness(): TabReadiness {
  if (hasPendingApiMutations()) return "busy";
  for (const guard of guards) {
    let state: UpdateReadiness;
    try {
      state = guard();
    } catch {
      return "busy";
    }
    if (state.busy) return "busy";
    if (state.dirty) return "dirty";
  }
  return "ready";
}
