import { t, type Locale } from "#lib/i18n.svelte.ts";

/** "Due for deletion", "1 day left" or "{n} days left". */
export function daysLeftLabel(days: number, locale?: Locale): string {
  if (days <= 0) return t("Due for deletion", locale);
  if (days === 1) return t("1 day left", locale);
  return t("{n} days left", locale).replace("{n}", String(days));
}
