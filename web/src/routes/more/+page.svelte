<script lang="ts">
  import { resolve } from "$app/paths";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import NavigationTile from "#lib/components/NavigationTile.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";

  const locale = $derived(session.data?.locale ?? "en");
  const user = $derived(session.data?.user ?? null);
</script>

<svelte:head><title>Stricknani · {t("More", locale)}</title></svelte:head>

<section
  class="more-page page-container page-container--compact"
  aria-labelledby="more-title"
>
  <PageHeader title={t("More", locale)} titleId="more-title" />
  <nav class="more-card more-links" aria-label={t("More pages", locale)}>
    <NavigationTile
      href={resolve("/create")}
      icon="create"
      title={t("Create", locale)}
      description={t("Start a project or a yarn, or import one.", locale)}
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
    <NavigationTile
      href={resolve("/trash")}
      icon="trash"
      title={t("Trash", locale)}
      description={t("Restore deleted items or delete them for good.", locale)}
    />
    <NavigationTile
      href={resolve("/activity")}
      icon="history"
      title={t("My activity", locale)}
      description={t("Recent changes to your projects and yarns.", locale)}
    />
  </nav>

  {#if user}
    <section class="more-card" aria-label={t("Account", locale)}>
      <NavigationTile
        href={resolve("/account")}
        aria-label={t("Account settings", locale)}
        title={t("Account settings", locale)}
        description={user.email}
      >
        {#snippet leading()}
          <UserAvatar avatarUrl={user.avatar_url ?? null} name={user.email} />
        {/snippet}
      </NavigationTile>
    </section>
  {/if}

  {#if session.data?.user?.is_admin}
    <nav class="more-card more-links" aria-label={t("Admin", locale)}>
      <NavigationTile
        href={resolve("/admin/users")}
        icon="admin"
        title={t("Admin", locale)}
        description={t("Manage accounts and review the audit log.", locale)}
      />
    </nav>
  {/if}
  <nav class="more-card more-links" aria-label={t("More navigation", locale)}>
    <NavigationTile
      href={resolve("/about")}
      icon="info"
      title={t("About", locale)}
      description={t("Version, links and credits.", locale)}
    />
  </nav>
</section>

<style>
  .more-page {
    max-width: 40rem;
    display: grid;
    gap: 0.85rem;
  }
</style>
