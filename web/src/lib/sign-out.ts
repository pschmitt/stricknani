import { goto } from "$app/navigation";
import { resolve } from "$app/paths";
import { page } from "$app/state";
import { publishFeedback } from "#lib/feedback.ts";
import { t, type Locale } from "#lib/i18n.svelte.ts";
import { session, signOut } from "#lib/session.svelte.ts";

/**
 * Sign out after leaving the current page, so its own unsaved-changes confirm
 * runs first. A cancelled navigation keeps the page, the draft and the session.
 */
export async function signOutFromApp(locale: Locale): Promise<void> {
  const ownerId = session.data?.user?.id;
  const origin = page.url.pathname + page.url.search + page.url.hash;
  await goto(resolve("/login"));
  if (page.url.pathname !== resolve("/login")) return;
  try {
    await signOut();
  } catch {
    const message = t("Could not sign out.", locale);
    if (ownerId !== undefined)
      publishFeedback({ ownerId, kind: "error", message });
    await goto(origin, { replaceState: true });
  }
}
