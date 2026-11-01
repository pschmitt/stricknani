<script lang="ts">
  import { resolve } from "$app/paths";
  import PreviewSection from "#lib/components/PreviewSection.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("More", locale)}</title></svelte:head>
<PreviewSection
  title={t("More", locale)}
  description={t(
    "Imports, tools and account settings will be added in later slices.",
    locale,
  )}
  {locale}
/>
<nav class="more-links" aria-label={t("More pages", locale)}>
  <a href={resolve("/imports")}>{t("Imports", locale)}</a>
  <a href={resolve("/categories")}>{t("Categories", locale)}</a>
  <a href={resolve("/gauge")}>{t("Gauge calculator", locale)}</a>
  <a href={resolve("/account")}>{t("Account", locale)}</a>
  {#if session.data?.user?.is_admin}
    <a href={resolve("/admin/users")}>{t("Admin", locale)}</a>
    <a href={resolve("/admin/audit")}>{t("Audit log", locale)}</a>
  {/if}
</nav>

<style>
  .more-links {
    display: flex;
    max-width: 48rem;
    flex-wrap: wrap;
    gap: 0.5rem 1rem;
    margin-top: 1rem;
  }

  .more-links a {
    display: inline-flex;
    min-height: 2.75rem;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 620;
  }
</style>
