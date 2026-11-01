<script lang="ts">
  import { setLocale, t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  const enabled = $derived(session.status === "ready" && session.data !== null);

  function chooseLocale(next: Locale) {
    setLocale(next);
    if (session.data) session.data = { ...session.data, locale: next };

    if (typeof document === "undefined" || typeof location === "undefined")
      return;
    const secure = location.protocol === "https:" ? "; Secure" : "";
    document.cookie = `language=${next}; Max-Age=31536000; Path=/; SameSite=Lax${secure}`;
  }
</script>

<div class="locale-switcher" role="group" aria-label={t("Language", locale)}>
  <button
    type="button"
    disabled={!enabled}
    aria-label={t("English", locale)}
    aria-pressed={locale === "en"}
    class:active={locale === "en"}
    onclick={() => chooseLocale("en")}>EN</button
  >
  <button
    type="button"
    disabled={!enabled}
    aria-label={t("German", locale)}
    aria-pressed={locale === "de"}
    class:active={locale === "de"}
    onclick={() => chooseLocale("de")}>DE</button
  >
</div>

<style>
  .locale-switcher {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 0.15rem;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0.15rem;
    background: var(--surface);
  }

  button {
    min-width: 2.75rem;
    min-height: 2.75rem;
    border: 0;
    border-radius: 0.5rem;
    color: var(--ink-muted);
    background: transparent;
    font: inherit;
    font-size: 0.85rem;
    font-weight: 700;
    cursor: pointer;
  }

  button.active {
    color: var(--accent-strong);
    background: var(--surface-raised);
  }

  button:disabled {
    cursor: wait;
  }
</style>
