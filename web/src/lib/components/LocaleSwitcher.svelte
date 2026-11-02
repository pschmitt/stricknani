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
    onclick={() => chooseLocale("en")}
  >
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
    <span>EN</span>
  </button>
  <button
    type="button"
    disabled={!enabled}
    aria-label={t("German", locale)}
    aria-pressed={locale === "de"}
    class:active={locale === "de"}
    onclick={() => chooseLocale("de")}
  >
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
    <span>DE</span>
  </button>
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
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.35rem;
    min-width: 2.75rem;
    min-height: 2.75rem;
    border: 0;
    border-radius: 0.5rem;
    color: var(--ink-muted);
    background: transparent;
    font: inherit;
    padding: 0 0.35rem;
    font-size: 0.85rem;
    font-weight: 700;
    cursor: pointer;
  }

  .language-flag {
    width: 0.875rem;
    height: 0.625rem;
    flex: 0 0 auto;
    border-radius: 0.1rem;
    box-shadow: 0 0 0 1px rgb(0 0 0 / 12%);
  }

  button.active {
    color: var(--accent-strong);
    background: var(--surface-raised);
  }

  button:disabled {
    cursor: wait;
  }
</style>
