<script lang="ts">
  import { onMount, type Snippet } from "svelte";
  import { afterNavigate, goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import {
    shouldRedirectExpiredSession,
    showPublicGauge,
  } from "#lib/app-shell.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import BrandHeader from "#lib/components/BrandHeader.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import PwaUpdate from "#lib/components/PwaUpdate.svelte";
  import SignInForm from "#lib/components/SignInForm.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { loadCatalog, msg, setLocale, t } from "#lib/i18n.svelte.ts";
  import { refreshSession, session } from "#lib/session.svelte.ts";
  import "../app.css";

  let { children }: { children: Snippet } = $props();

  const locale = $derived(session.data?.locale ?? "en");
  const pathname = $derived(page.url.pathname);
  const signupRoute = $derived(pathname === linkTo("/signup"));
  const user = $derived(session.data?.user ?? null);
  const publicGauge = $derived(
    showPublicGauge(pathname, linkTo("/gauge"), session.status, user !== null),
  );
  const adminRoute = $derived(
    pathname.startsWith(resolve("/admin/users")) ||
      pathname.startsWith(resolve("/admin/audit")) ||
      pathname.startsWith("/app/admin/resources"),
  );
  const canManageAdmin = $derived(
    user?.is_admin === true &&
      session.data?.capabilities.admin_actions === true,
  );
  const routeIsAdminOnly = $derived(adminRoute && !canManageAdmin);
  let canGoBack = $state(false);

  const mainNavigation = [
    { href: "/projects", label: msg("Projects"), icon: "projects" },
    { href: "/yarns", label: msg("Yarns"), icon: "yarns" },
    { href: "/create", label: msg("Create"), icon: "create" },
    { href: "/more", label: msg("More"), icon: "more" },
  ] as const;

  const secondaryNavigation = [
    { href: "/imports", label: msg("Imports"), icon: "imports" },
    { href: "/categories", label: msg("Categories"), icon: "categories" },
    { href: "/gauge", label: msg("Gauge calculator"), icon: "gauge" },
  ] as const;

  const avatarUrl = $derived(user?.avatar_url ?? null);

  afterNavigate(refreshCanGoBack);

  onMount(() => {
    refreshCanGoBack();
    window.addEventListener("popstate", refreshCanGoBack);
    void loadCatalog();
    void refreshSession();
    return () => window.removeEventListener("popstate", refreshCanGoBack);
  });

  $effect(() => {
    setLocale(locale);
    if (typeof document !== "undefined") document.documentElement.lang = locale;
    if (
      shouldRedirectExpiredSession(
        session.expired,
        session.status,
        pathname,
        linkTo("/login"),
        publicGauge,
      )
    ) {
      void goto(linkTo("/login"), { replaceState: true });
    }
  });

  type AppPath =
    | "/"
    | "/login"
    | "/signup"
    | "/projects"
    | "/projects/new"
    | "/yarns"
    | "/yarns/new"
    | "/create"
    | "/more"
    | "/imports"
    | "/categories"
    | "/gauge"
    | "/account"
    | "/admin/users"
    | "/admin/audit";

  function linkTo(path: AppPath): string {
    return resolve(path);
  }

  function refreshCanGoBack() {
    canGoBack = window.history.length > 1;
  }

  function goBack() {
    if (window.history.length > 1) window.history.back();
  }

  function isActive(path: AppPath): boolean {
    if (path === "/projects" && pathname === linkTo("/")) return true;
    const fullPath = linkTo(path);
    return pathname === fullPath || pathname.startsWith(`${fullPath}/`);
  }
</script>

<svelte:head>
  <meta
    name="viewport"
    content="width=device-width, initial-scale=1, viewport-fit=cover"
  />
  <meta name="color-scheme" content="light dark" />
  <title>Stricknani</title>
  <meta
    name="description"
    content={t(
      "A quiet place to keep your knitting projects and yarn close.",
      locale,
    )}
  />
  <link rel="manifest" href={`${resolve("/")}manifest.webmanifest`} />
</svelte:head>

<a class="skip-link" href="#main-content">{t("Skip to content", locale)}</a>
<PwaUpdate />

{#if publicGauge}
  <div class="public-tool-shell">
    <header class="public-tool-header">
      <BrandHeader href={linkTo("/login")} />
      <LocaleSwitcher />
      <a class="public-sign-in" href={linkTo("/login")}
        ><ActionLabel icon="sign-in">{t("Sign in", locale)}</ActionLabel></a
      >
    </header>
    <main id="main-content" class="public-tool-main">
      {@render children()}
    </main>
  </div>
{:else if session.status === "loading"}
  <main
    id="main-content"
    class="boot-screen"
    aria-busy="true"
    aria-live="polite"
  >
    <div class="boot-content">
      <BrandHeader href={linkTo("/login")} />
      <p>{t("Opening your workspace…", locale)}</p>
      <span class="spinner" aria-hidden="true"></span>
    </div>
  </main>
{:else if session.status === "error"}
  <main id="main-content" class="error-screen">
    <section class="connection-panel" aria-labelledby="connection-title">
      <BrandHeader href={linkTo("/")} />
      <h1 id="connection-title">
        {t("Your workspace is taking a moment.", locale)}
      </h1>
      <p>
        {session.error?.message ??
          t("The session could not be loaded.", locale)}
      </p>
      <button
        class="primary-button"
        type="button"
        onclick={() => void refreshSession()}
      >
        {t("Try again", locale)}
      </button>
    </section>
  </main>
{:else if !user && signupRoute}
  <div class="signed-out-screen">
    <main id="main-content" class="signup-route-main">
      {@render children()}
    </main>
  </div>
{:else if !user}
  <div class="signed-out-screen">
    <SignInForm />
  </div>
{:else}
  <div class="app-shell">
    <header class="app-header page-header">
      {#if canGoBack}
        <button
          class="shell-back-button"
          type="button"
          aria-label={t("Back", locale)}
          onclick={goBack}
        >
          <NavIcon name="previous" />
        </button>
      {/if}
      <BrandHeader href={linkTo("/")} />
      <a
        class="header-account"
        href={linkTo("/account")}
        aria-label={t("Account", locale)}
      >
        <UserAvatar {avatarUrl} name={user.email} />
      </a>
    </header>

    <aside class="desktop-rail" aria-label={t("Main navigation", locale)}>
      <nav class="primary-nav" aria-label={t("Your library", locale)}>
        {#each mainNavigation as item}
          <a
            class:active={isActive(item.href)}
            aria-current={isActive(item.href) ? "page" : undefined}
            href={linkTo(item.href)}
          >
            <span class="nav-icon"><NavIcon name={item.icon} /></span>
            <span>{t(item.label, locale)}</span>
          </a>
        {/each}
      </nav>

      <div class="rail-divider"></div>
      <p class="nav-caption">{t("Workspace", locale)}</p>
      <nav class="secondary-nav" aria-label={t("Workspace navigation", locale)}>
        {#each secondaryNavigation as item}
          <a
            class:active={isActive(item.href)}
            aria-current={isActive(item.href) ? "page" : undefined}
            href={linkTo(item.href)}
          >
            <span class="nav-icon"><NavIcon name={item.icon} /></span>
            <span>{t(item.label, locale)}</span>
          </a>
        {/each}
        {#if canManageAdmin}
          <a
            class:active={isActive("/admin/users")}
            aria-current={isActive("/admin/users") ? "page" : undefined}
            href={linkTo("/admin/users")}
          >
            <span class="nav-icon"><NavIcon name="admin" /></span>
            <span>{t("Admin", locale)}</span>
          </a>
          <a
            class:active={isActive("/admin/audit")}
            aria-current={isActive("/admin/audit") ? "page" : undefined}
            href={linkTo("/admin/audit")}
          >
            <span class="nav-icon"><NavIcon name="audit" /></span>
            <span>{t("Audit log", locale)}</span>
          </a>
        {/if}
      </nav>
    </aside>

    <div class="content-column">
      {#if routeIsAdminOnly}
        <main id="main-content" class="content-main">
          <section class="notice-panel" aria-labelledby="forbidden-title">
            <p class="section-mark">{t("Account access", locale)}</p>
            <h1 id="forbidden-title">
              {t("You do not have access to this page.", locale)}
            </h1>
            <p>{t("Ask an administrator if you need access.", locale)}</p>
            <a class="inline-link" href={linkTo("/projects")}
              >{t("Back to projects", locale)}</a
            >
          </section>
        </main>
      {:else}
        <main id="main-content" class="content-main">
          {@render children()}
        </main>
      {/if}

      <nav class="mobile-navigation" aria-label={t("Main navigation", locale)}>
        {#each mainNavigation as item}
          <a
            class:active={isActive(item.href)}
            aria-current={isActive(item.href) ? "page" : undefined}
            href={linkTo(item.href)}
          >
            <span class="nav-icon"><NavIcon name={item.icon} /></span>
            <span>{t(item.label, locale)}</span>
          </a>
        {/each}
      </nav>
    </div>
  </div>
{/if}

<style>
  .public-tool-shell {
    min-height: 100svh;
    background: var(--canvas);
  }

  .public-tool-header {
    display: flex;
    min-height: 4.25rem;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    border-bottom: 1px solid var(--line);
    padding: 0.5rem max(1rem, env(safe-area-inset-left));
    background: var(--surface);
  }

  .public-tool-header > * {
    min-height: 44px;
    display: inline-flex;
    align-items: center;
  }

  .public-tool-header :global(.brand-lockup) {
    text-decoration: none;
  }

  .signup-route-main {
    display: flex;
    width: 100%;
    justify-content: center;
  }

  .public-sign-in {
    justify-content: center;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.4rem 0.8rem;
    color: var(--accent-strong);
    background: var(--surface-raised);
    font-weight: 650;
    text-decoration: none;
  }

  .public-tool-main {
    width: min(100%, 56rem);
    margin-inline: auto;
    padding: clamp(1rem, 4vw, 2.5rem);
  }

  @media (max-width: 380px) {
    .public-tool-header {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      justify-content: initial;
      gap: 0.35rem;
      padding-inline: 0.5rem;
    }

    .public-tool-header :global(.brand-lockup) {
      min-width: 0;
      overflow: hidden;
      gap: 0.4rem;
    }

    .public-tool-header :global(.brand-copy) {
      overflow: hidden;
    }

    .public-tool-header :global(.brand-title) {
      font-size: 0.95rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .public-sign-in {
      padding-inline: 0.5rem;
      font-size: 0.9rem;
    }
  }
</style>
