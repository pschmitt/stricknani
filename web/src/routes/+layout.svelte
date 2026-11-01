<script lang="ts">
  import { onMount, type Snippet } from "svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import {
    shouldRedirectExpiredSession,
    showPublicGauge,
  } from "#lib/app-shell.ts";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import SignInForm from "#lib/components/SignInForm.svelte";
  import { loadCatalog, msg, setLocale, t } from "#lib/i18n.svelte.ts";
  import { refreshSession, session, signOut } from "#lib/session.svelte.ts";
  import "../app.css";

  let { children }: { children: Snippet } = $props();
  let logoutError = $state<string | null>(null);

  const locale = $derived(session.data?.locale ?? "en");
  const pathname = $derived(page.url.pathname);
  const signupRoute = $derived(pathname === linkTo("/signup"));
  const user = $derived(session.data?.user ?? null);
  const publicGauge = $derived(
    showPublicGauge(pathname, linkTo("/gauge"), session.status, user !== null),
  );
  const adminRoute = $derived(
    pathname.startsWith(resolve("/admin/users")) ||
      pathname.startsWith(resolve("/admin/audit")),
  );
  const routeIsAdminOnly = $derived(adminRoute && !user?.is_admin);

  const mainNavigation = [
    { href: "/projects", label: msg("Projects"), marker: "P" },
    { href: "/yarns", label: msg("Yarns"), marker: "Y" },
    { href: "/create", label: msg("Create"), marker: "+" },
    { href: "/more", label: msg("More"), marker: "•••" },
  ] as const;

  const secondaryNavigation = [
    { href: "/imports", label: msg("Imports") },
    { href: "/categories", label: msg("Categories") },
    { href: "/gauge", label: msg("Gauge calculator") },
    { href: "/account", label: msg("Account") },
  ] as const;

  onMount(() => {
    void loadCatalog();
    void refreshSession();
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

  function isActive(path: AppPath): boolean {
    if (path === "/projects" && pathname === linkTo("/")) return true;
    const fullPath = linkTo(path);
    return pathname === fullPath || pathname.startsWith(`${fullPath}/`);
  }

  async function handleSignOut() {
    logoutError = null;
    try {
      await signOut();
      await goto(linkTo("/login"));
    } catch (error) {
      logoutError =
        error instanceof Error
          ? error.message
          : t("Could not sign out.", locale);
    }
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
</svelte:head>

<a class="skip-link" href="#main-content">{t("Skip to content", locale)}</a>

{#if publicGauge}
  <div class="public-tool-shell">
    <header class="public-tool-header">
      <a class="wordmark" href={linkTo("/login")}>
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>Stricknani</span>
      </a>
      <LocaleSwitcher />
      <a class="public-sign-in" href={linkTo("/login")}
        >{t("Sign in", locale)}</a
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
      <span class="brand-mark" aria-hidden="true"><span></span></span>
      <p>{t("Opening your workspace…", locale)}</p>
      <span class="spinner" aria-hidden="true"></span>
    </div>
  </main>
{:else if session.status === "error"}
  <main id="main-content" class="error-screen">
    <section class="connection-panel" aria-labelledby="connection-title">
      <a class="wordmark" href={linkTo("/")}>
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>Stricknani</span>
      </a>
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
    <aside class="desktop-rail" aria-label={t("Main navigation", locale)}>
      <a class="wordmark rail-wordmark" href={linkTo("/")}>
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>Stricknani</span>
      </a>

      <nav class="primary-nav" aria-label={t("Your library", locale)}>
        {#each mainNavigation as item}
          <a
            class:active={isActive(item.href)}
            aria-current={isActive(item.href) ? "page" : undefined}
            href={linkTo(item.href)}
          >
            <span class="nav-marker" aria-hidden="true">{item.marker}</span>
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
            {t(item.label, locale)}
          </a>
        {/each}
        {#if user.is_admin}
          <a
            class:active={isActive("/admin/users")}
            aria-current={isActive("/admin/users") ? "page" : undefined}
            href={linkTo("/admin/users")}>{t("Admin", locale)}</a
          >
          <a
            class:active={isActive("/admin/audit")}
            aria-current={isActive("/admin/audit") ? "page" : undefined}
            href={linkTo("/admin/audit")}>{t("Audit log", locale)}</a
          >
        {/if}
      </nav>

      <div class="rail-footer">
        <div class="account-summary">
          <span class="avatar" aria-hidden="true"
            >{user.email.slice(0, 1).toUpperCase()}</span
          >
          <span class="account-email" title={user.email}>{user.email}</span>
        </div>
        <button
          class="text-button sign-out"
          type="button"
          onclick={handleSignOut}>{t("Sign out", locale)}</button
        >
      </div>
    </aside>

    <div class="mobile-header">
      <a class="wordmark" href={linkTo("/")}>
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>Stricknani</span>
      </a>
      <a
        class="mobile-account"
        href={linkTo("/account")}
        aria-label={t("Account", locale)}
      >
        <span class="avatar" aria-hidden="true"
          >{user.email.slice(0, 1).toUpperCase()}</span
        >
      </a>
      <button class="mobile-sign-out" type="button" onclick={handleSignOut}
        >{t("Sign out", locale)}</button
      >
    </div>

    <div class="content-column">
      <header class="page-header">
        <p class="header-user">{user.email}</p>
        <div class="header-actions">
          <LocaleSwitcher />
          <button
            class="text-button header-sign-out"
            type="button"
            onclick={handleSignOut}>{t("Sign out", locale)}</button
          >
        </div>
      </header>

      {#if logoutError}
        <div class="notice notice-error" role="alert">{logoutError}</div>
      {/if}

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
            <span class="nav-marker" aria-hidden="true">{item.marker}</span>
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

  .public-tool-header .wordmark {
    text-decoration: none;
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 0.75rem;
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

    .public-tool-header .wordmark {
      min-width: 0;
      overflow: hidden;
      gap: 0.4rem;
      font-size: 0.95rem;
    }

    .public-tool-header .wordmark > span:last-child {
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
