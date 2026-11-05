import { t, type Locale } from "#lib/i18n.svelte.ts";

/** "1 linked yarn" / "3 linked yarns" (message ids stay literal for the extractor). */
export function linkedYarnsLabel(count: number, locale: Locale): string {
  return count === 1
    ? t("1 linked yarn", locale)
    : t("{count} linked yarns", locale).replace("{count}", String(count));
}

/** "Also move 1 linked yarn" / "Also move 3 linked yarns". */
export function moveYarnsLabel(count: number, locale: Locale): string {
  return count === 1
    ? t("Also move 1 linked yarn", locale)
    : t("Also move {count} linked yarns", locale).replace(
        "{count}",
        String(count),
      );
}
