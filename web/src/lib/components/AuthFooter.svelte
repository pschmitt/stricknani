<script lang="ts">
  import { resolve } from "$app/paths";
  import { androidAction } from "#lib/app-links.ts";
  import { appVersion } from "#lib/app-version.ts";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  let { class: className = "" }: { class?: string } = $props();

  const locale = $derived(session.data?.locale ?? "en");
</script>

<footer class="auth-footer {className}">
  <nav class="auth-links" aria-label={t("More", locale)}>
    <a href={resolve("/privacy")}>{t("Privacy Policy", locale)}</a>
    <a href={androidAction().url} target="_blank" rel="noopener noreferrer"
      >{t("Get the Android app", locale)}</a
    >
    <a href={resolve("/gauge")}>{t("Gauge calculator", locale)}</a>
  </nav>
  <div class="auth-footer-meta">
    <LocaleSwitcher />
    <span class="auth-wordmark">Stricknani {appVersion}</span>
  </div>
</footer>

<style>
  .auth-footer {
    display: grid;
    justify-items: center;
    gap: 0.25rem;
    margin-top: 1.25rem;
  }

  .auth-links {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0 1.1rem;
  }

  .auth-links a {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    color: var(--ink-muted);
    font-size: 0.88rem;
    font-weight: 600;
    text-decoration: underline;
    text-decoration-color: transparent;
    text-underline-offset: 3px;
  }

  .auth-links a:hover,
  .auth-links a:focus-visible {
    color: var(--accent-strong);
    text-decoration-color: currentcolor;
  }

  .auth-footer-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 0.25rem 1rem;
  }

  .auth-wordmark {
    color: var(--ink-muted);
    font-size: 0.75rem;
    font-weight: 600;
    opacity: 0.8;
  }
</style>
