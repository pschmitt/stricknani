<script lang="ts">
  import { resolve } from "$app/paths";
  import { androidAction } from "#lib/app-links.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import Button from "#lib/components/Button.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { signOutFromApp } from "#lib/sign-out.ts";
  import NavigationTile from "#lib/components/NavigationTile.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";

  const locale = $derived(session.data?.locale ?? "en");
  const androidApp = androidAction();
  const user = $derived(session.data?.user ?? null);
  let signOutBusy = $state(false);

  async function handleSignOut() {
    if (signOutBusy) return;
    signOutBusy = true;
    try {
      await signOutFromApp(locale);
    } finally {
      signOutBusy = false;
    }
  }
</script>

<svelte:head><title>Stricknani · {t("More", locale)}</title></svelte:head>

<section
  class="more-page page-container page-container--compact"
  aria-labelledby="more-title"
>
  <PageHeader title={t("More", locale)} titleId="more-title" />
  <nav class="more-links" aria-label={t("More pages", locale)}>
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
      href={androidApp.url}
      target="_blank"
      rel="noopener noreferrer"
      icon="download"
      title={t("Android app", locale)}
      description={t(
        "Knit offline with the Stricknani app for Android.",
        locale,
      )}
    />
    {#if session.data?.user?.is_admin}
      <NavigationTile
        href={resolve("/admin/users")}
        icon="admin"
        title={t("Admin", locale)}
        description={t("Manage accounts and review the audit log.", locale)}
      />
    {/if}
    <NavigationTile
      href={resolve("/about")}
      icon="info"
      title={t("About", locale)}
      description={t("Version, links and credits.", locale)}
    />
  </nav>

  {#if user}
    <section class="more-account" aria-label={t("Account", locale)}>
      <a
        class="more-account__link"
        href={resolve("/account")}
        aria-label={t("Account settings", locale)}
      >
        <UserAvatar
          avatarUrl={user.avatar_url ?? null}
          name={user.email}
          size="large"
        />
        <span class="more-account__copy">
          <span class="more-account__title"
            >{t("Account settings", locale)}</span
          >
          <span class="more-account__email">{user.email}</span>
        </span>
      </a>
      <Button
        variant="secondary"
        icon="sign-out"
        disabled={signOutBusy}
        onclick={() => void handleSignOut()}
      >
        {signOutBusy ? t("Working…", locale) : t("Sign out", locale)}
      </Button>
    </section>
  {/if}
</section>

<style>
  .more-links {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
    gap: 0.65rem;
    margin-top: 0.85rem;
  }

  .more-account {
    display: grid;
    gap: 0.65rem;
    margin-top: 1.25rem;
    padding-top: 1.25rem;
    border-top: 1px solid var(--line);
  }

  .more-account__link {
    display: flex;
    min-height: 44px;
    align-items: center;
    gap: 0.85rem;
    padding: 0.65rem 0.85rem;
    border-radius: var(--radius-md);
    color: var(--ink);
    background: var(--surface-container);
    text-decoration: none;
  }

  .more-account__link:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .more-account__copy {
    display: grid;
    min-width: 0;
  }

  .more-account__title {
    font-weight: 700;
  }

  .more-account__email {
    overflow: hidden;
    color: var(--ink-muted);
    font-size: 0.85rem;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
