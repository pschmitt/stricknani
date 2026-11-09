<script module lang="ts">
  import { setLocale, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  /** Switch the UI language and remember it in the language cookie. */
  export function chooseLocale(next: Locale): void {
    setLocale(next);
    if (session.data) session.data = { ...session.data, locale: next };

    if (typeof document === "undefined" || typeof location === "undefined")
      return;
    const secure = location.protocol === "https:" ? "; Secure" : "";
    document.cookie = `language=${next}; Max-Age=31536000; Path=/; SameSite=Lax${secure}`;
  }
</script>

<script lang="ts">
  import { t } from "#lib/i18n.svelte.ts";
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";

  const locale = $derived(session.data?.locale ?? "en");
  const enabled = $derived(session.status === "ready" && session.data !== null);
  const options = $derived<
    { value: Locale; label: string; ariaLabel: string }[]
  >([
    { value: "en", label: "EN", ariaLabel: t("English", locale) },
    { value: "de", label: "DE", ariaLabel: t("German", locale) },
  ]);
</script>

<div class="locale-switcher">
  <SegmentedControl
    {options}
    value={locale}
    label={t("Language", locale)}
    size="compact"
    disabled={!enabled}
    onchange={chooseLocale}
  >
    {#snippet content(option)}
      {#if option.value === "en"}
        <svg
          class="language-flag"
          aria-hidden="true"
          focusable="false"
          viewBox="0 0 60 40"
        >
          <rect width="60" height="40" fill="#21468b" />
          <path d="m0 0 60 40m0-40L0 40" stroke="#fff" stroke-width="11" />
          <path d="m0 0 60 40m0-40L0 40" stroke="#c8102e" stroke-width="5" />
          <path d="M30 0v40M0 20h60" stroke="#fff" stroke-width="16" />
          <path d="M30 0v40M0 20h60" stroke="#c8102e" stroke-width="8" />
        </svg>
      {:else}
        <svg
          class="language-flag"
          aria-hidden="true"
          focusable="false"
          viewBox="0 0 60 40"
        >
          <rect width="60" height="40" fill="#ffce00" />
          <rect width="60" height="26.67" fill="#dd0000" />
          <rect width="60" height="13.33" fill="#000" />
        </svg>
      {/if}
      <span>{option.label}</span>
    {/snippet}
  </SegmentedControl>
</div>

<style>
  .locale-switcher {
    display: inline-flex;
    flex: 0 0 auto;
  }

  .language-flag {
    width: 0.875rem;
    height: 0.625rem;
    flex: 0 0 auto;
    border-radius: 0.1rem;
    box-shadow: 0 0 0 1px rgb(0 0 0 / 12%);
  }
</style>
