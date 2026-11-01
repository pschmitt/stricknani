<script lang="ts">
  import { resolve } from "$app/paths";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("More", locale)}</title></svelte:head>

<section class="more-page" aria-labelledby="more-title">
  <p class="section-mark">Stricknani</p>
  <h1 id="more-title">{t("More", locale)}</h1>
  <p class="more-intro">
    {t("Tools and settings for your knitting library.", locale)}
  </p>
  <nav class="more-links" aria-label={t("More pages", locale)}>
    <a href={resolve("/imports")}>
      <strong>{t("Imports", locale)}</strong>
      <small>{t("Available now", locale)}</small>
    </a>
    <a href={resolve("/categories")}>
      <strong>{t("Categories", locale)}</strong>
      <small>{t("Available now", locale)}</small>
    </a>
    <a href={resolve("/gauge")}>
      <strong>{t("Gauge calculator", locale)}</strong>
      <small>{t("Available now", locale)}</small>
    </a>
    <a href={resolve("/account")}>
      <strong>{t("Account", locale)}</strong>
      <small>{t("Available now", locale)}</small>
    </a>
    {#if session.data?.user?.is_admin}
      <a href={resolve("/admin/users")}>
        <strong>{t("Admin", locale)}</strong>
        <small>{t("In progress", locale)}</small>
      </a>
      <a href={resolve("/admin/audit")}>
        <strong>{t("Audit log", locale)}</strong>
        <small>{t("In progress", locale)}</small>
      </a>
    {/if}
  </nav>
</section>

<style>
  .more-page {
    max-width: 48rem;
    border: 1px solid var(--line);
    border-radius: 1.1rem;
    padding: clamp(1.4rem, 4vw, 2.6rem);
    background: var(--surface);
  }

  .section-mark {
    margin: 0 0 0.4rem;
    color: var(--accent-strong);
    font-size: 0.88rem;
    font-weight: 650;
  }

  h1 {
    margin: 0;
    font-size: clamp(2rem, 6vw, 3rem);
    font-weight: 680;
    letter-spacing: -0.06em;
  }

  .more-intro {
    max-width: 40rem;
    margin: 0.75rem 0 0;
    color: var(--ink-muted);
    line-height: 1.6;
  }

  .more-links {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
    gap: 0.65rem;
    margin-top: 1.4rem;
  }

  .more-links a {
    display: grid;
    align-content: center;
    min-height: 44px;
    gap: 0.15rem;
    border: 1px solid var(--line);
    border-radius: 0.75rem;
    padding: 0.7rem 0.85rem;
    color: var(--accent-strong);
    background: var(--surface-raised);
    font-weight: 650;
    text-decoration: none;
  }

  .more-links small {
    color: var(--ink-muted);
    font-size: 0.82rem;
    font-weight: 500;
  }
</style>
