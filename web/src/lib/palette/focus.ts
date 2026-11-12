/**
 * Whether the search field must take focus back after losing it.
 *
 * Focus is lost without a new target (relatedTarget is null) when SvelteKit
 * blurs the active element at the start of a navigation, for instance the
 * redirect that follows signing in, which can still be running when the
 * palette opens. Typing and Enter then go to the page behind the dialog.
 * Moving focus to another element inside the dialog (a row, the "Show more"
 * button) is deliberate and left alone.
 */
export function shouldRefocusSearch<T>(options: {
  open: boolean;
  relatedTarget: T | null;
  contains: (target: T) => boolean;
}): boolean {
  if (!options.open) return false;
  const { relatedTarget } = options;
  if (relatedTarget === null) return true;
  return !options.contains(relatedTarget);
}
