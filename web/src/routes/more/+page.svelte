<script lang="ts">
  import { resolve } from "$app/paths";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import NavigationTile from "#lib/components/NavigationTile.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";

  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("More", locale)}</title></svelte:head>

<section
  class="more-page page-container page-container--compact"
  aria-labelledby="more-title"
>
  <PageHeader title={t("More", locale)} titleId="more-title" />
  <nav class="more-links" aria-label={t("More pages", locale)}>
    <NavigationTile
      href={resolve("/imports")}
      icon="imports"
      title={t("Imports", locale)}
      description={t(
        "Create a project draft from text, a file, or a pattern link.",
        locale,
      )}
    />
    <NavigationTile
      href={resolve("/categories")}
      icon="categories"
      title={t("Categories", locale)}
      description={t(
        "Organize projects with your own category labels.",
        locale,
      )}
    />
    <NavigationTile
      href={resolve("/gauge")}
      icon="gauge"
      title={t("Gauge calculator", locale)}
      description={t(
        "Turn swatch measurements into stitch and row counts.",
        locale,
      )}
    />
    {#if session.data?.user?.is_admin}
      <NavigationTile
        href={resolve("/admin/users")}
        icon="admin"
        title={t("Admin", locale)}
        description={t("Manage accounts, sign-in access, and roles.", locale)}
      />
      <NavigationTile
        href={resolve("/admin/audit")}
        icon="audit"
        title={t("Audit log", locale)}
        description={t(
          "Review recent changes to projects, yarn, and categories.",
          locale,
        )}
      />
    {/if}
  </nav>
</section>

<style>
  .more-links {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
    gap: 0.65rem;
    margin-top: 0.85rem;
  }
</style>
