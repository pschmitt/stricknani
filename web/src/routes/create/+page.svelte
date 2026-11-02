<script lang="ts">
  import { resolve } from "$app/paths";
  import NavigationTile from "#lib/components/NavigationTile.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("Create", locale)}</title></svelte:head>

<section
  class="create-page page-container page-container--compact"
  aria-labelledby="create-title"
>
  <PageHeader
    title={t("What would you like to add?", locale)}
    titleId="create-title"
  />
  <div class="create-options">
    <NavigationTile
      href={resolve("/projects/new")}
      icon="projects"
      title={t("New project", locale)}
      description={t("Plan the next thing you want to knit.", locale)}
    />
    <NavigationTile
      href={resolve("/yarns/new")}
      icon="yarns"
      title={t("New yarn", locale)}
      description={t("Keep a note of a yarn you own.", locale)}
    />
  </div>
</section>

<style>
  .create-options {
    display: grid;
    gap: 0.7rem;
    margin-top: 0.85rem;
  }
</style>
