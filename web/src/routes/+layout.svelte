<script lang="ts">
  import { onMount, untrack, type Snippet } from "svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import {
    shouldRedirectExpiredSession,
    showPublicPage,
  } from "#lib/app-shell.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import ActionFeedback from "#lib/components/ActionFeedback.svelte";
  import {
    parseErrorReporting,
    syncErrorReporting,
  } from "#lib/error-reporting.ts";
  import { clearFeedback, feedback } from "#lib/feedback.ts";
  import BrandHeader from "#lib/components/BrandHeader.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import PwaUpdate from "#lib/components/PwaUpdate.svelte";
  import { routeParent } from "#lib/route-parent.ts";
  import SignInForm from "#lib/components/SignInForm.svelte";
  import SharedButton from "#lib/components/Button.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { loadCatalog, msg, setLocale, t } from "#lib/i18n.svelte.ts";
  import { isMoreActive, isPathActive } from "#lib/more-nav.ts";
  import { refreshSession, session } from "#lib/session.svelte.ts";
  import { initTheme } from "#lib/theme.svelte.ts";
  import "../app.css";

  let { children }: { children: Snippet } = $props();

  const locale = $derived(session.data?.locale ?? "en");
  const pathname = $derived(page.url.pathname);
  const parentHref = $derived(routeParent(pathname, resolve("/")));
  const signupRoute = $derived(pathname === linkTo("/signup"));
  const user = $derived(session.data?.user ?? null);
  $effect(() => {
    const ownerId = user?.id ?? null;
    untrack(() => {
      if ($feedback && $feedback.ownerId !== ownerId) clearFeedback();
    });
  });
  // Optional error reporting: starts only when the server offers it and the
  // person has not turned it off; an opt-out stops it right away.
  $effect(() => {
    const info = parseErrorReporting(session.data?.error_reporting);
    void syncErrorReporting(info).catch(() => {});
  });
  const publicGauge = $derived(
    showPublicPage(
      pathname,
      [
        linkTo("/gauge"),
        linkTo("/privacy"),
        linkTo("/about"),
        linkTo("/delete-account"),
      ],
      session.status,
      user !== null,
    ),
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

  const mainNavigation = [
    { href: "/", label: msg("Home"), icon: "home" },
    { href: "/projects", label: msg("Projects"), icon: "projects" },
    { href: "/yarns", label: msg("Yarns"), icon: "yarns" },
    { href: "/create", label: msg("Create"), icon: "create" },
  ] as const;

  /** Phones: Create lives under More, so the bar has four tabs. */
  const phoneNavigation = mainNavigation.filter(
    (item) => item.href !== "/create",
  );
  const moreActive = $derived(
    isMoreActive(pathname, (path) => resolve(path as "/more")),
  );

  const secondaryNavigation = [
    { href: "/categories", label: msg("Categories"), icon: "categories" },
    { href: "/gauge", label: msg("Gauge calculator"), icon: "gauge" },
  ] as const;

  const avatarUrl = $derived(user?.avatar_url ?? null);

  onMount(() => {
    void loadCatalog();
    void refreshSession();
    const stopTheme = initTheme();
    // The glass part of the header fades in as content scrolls under it.
    const root = document.documentElement;
    let frame = 0;
    const GLASS_RAMP_PX = 40;
    const syncGlass = () => {
      frame = 0;
      const glass = Math.min(1, Math.max(0, window.scrollY / GLASS_RAMP_PX));
      root.style.setProperty("--header-glass", String(glass));
    };
    const scheduleGlass = () => {
      if (!frame) frame = requestAnimationFrame(syncGlass);
    };
    syncGlass();
    window.addEventListener("scroll", scheduleGlass, { passive: true });
    return () => {
      stopTheme();
      window.removeEventListener("scroll", scheduleGlass);
      if (frame) cancelAnimationFrame(frame);
      root.style.removeProperty("--header-glass");
    };
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
        linkTo("/signup"),
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
    | "/privacy"
    | "/about"
    | "/delete-account"
    | "/account"
    | "/admin/users"
    | "/admin/audit";

  function linkTo(path: AppPath): string {
    return resolve(path);
  }

  function isActive(path: AppPath): boolean {
    if (path === "/") return pathname === linkTo("/");
    return isPathActive(pathname, linkTo(path));
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
<ActionFeedback ownerId={user?.id ?? null} {locale} />

{#if publicGauge}
  <div class="public-tool-shell">
    <header class="public-tool-header">
      <BrandHeader href={linkTo("/login")} />
      <LocaleSwitcher />
      <a
        class="ui-button ui-button--secondary ui-button--md"
        href={linkTo("/login")}
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
      <SharedButton
        variant="primary"
        type="button"
        onclick={() => void refreshSession()}
        icon="refresh"
      >
        {t("Try again", locale)}
      </SharedButton>
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
      <BrandHeader
        href={linkTo("/")}
        {parentHref}
        parentLabel={t("Back", locale)}
        reserveParentSlot={true}
      />
    </header>

    <aside class="desktop-rail" aria-label={t("Main navigation", locale)}>
      <nav class="primary-nav" aria-label={t("Your library", locale)}>
        {#each mainNavigation as item}
          <a
            class:active={isActive(item.href)}
            aria-current={isActive(item.href) ? "page" : undefined}
            data-tone={item.icon}
            href={linkTo(item.href)}
          >
            <span class="nav-icon" data-tone={item.icon}
              ><NavIcon name={item.icon} /></span
            >
            <span>{t(item.label, locale)}</span>
          </a>
        {/each}
      </nav>

      <div class="rail-divider"></div>
      <p class="nav-caption">{t("More", locale)}</p>
      <nav class="secondary-nav" aria-label={t("More navigation", locale)}>
        {#each secondaryNavigation as item}
          <a
            class:active={isActive(item.href)}
            aria-current={isActive(item.href) ? "page" : undefined}
            data-tone={item.icon}
            href={linkTo(item.href)}
          >
            <span class="nav-icon" data-tone={item.icon}
              ><NavIcon name={item.icon} /></span
            >
            <span>{t(item.label, locale)}</span>
          </a>
        {/each}
      </nav>
      <nav class="secondary-nav rail-footer" aria-label={t("Account", locale)}>
        <a
          class="rail-account"
          aria-label={t("Account", locale)}
          class:active={isActive("/account")}
          aria-current={isActive("/account") ? "page" : undefined}
          data-tone="account"
          href={linkTo("/account")}
        >
          <UserAvatar {avatarUrl} name={user.email} />
          <span class="rail-account-text">
            <span class="rail-account-title">{t("Account", locale)}</span>
            <span class="rail-account-email">{user.email}</span>
          </span>
        </a>
        {#if canManageAdmin}
          <a
            class:active={isActive("/admin/users") || isActive("/admin/audit")}
            aria-current={isActive("/admin/users") || isActive("/admin/audit")
              ? "page"
              : undefined}
            data-tone="admin"
            href={linkTo("/admin/users")}
          >
            <span class="nav-icon" data-tone="admin"
              ><NavIcon name="admin" /></span
            >
            <span>{t("Admin", locale)}</span>
          </a>
        {/if}
        <a
          class:active={isActive("/about")}
          aria-current={isActive("/about") ? "page" : undefined}
          data-tone="info"
          href={linkTo("/about")}
        >
          <span class="nav-icon" data-tone="info"><NavIcon name="info" /></span>
          <span>{t("About", locale)}</span>
        </a>
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
        {#each phoneNavigation as item}
          <a
            class:active={isActive(item.href)}
            aria-current={isActive(item.href) ? "page" : undefined}
            data-tone={item.icon}
            href={linkTo(item.href)}
          >
            <span class="nav-icon" data-tone={item.icon}
              ><NavIcon name={item.icon} /></span
            >
            <span>{t(item.label, locale)}</span>
          </a>
        {/each}
        <!-- The avatar replaces the "more" glyph: it is decorative (the
             link is named "More"); without an avatar the glyph shows. -->
        <a
          class="more-tab"
          class:active={moreActive}
          aria-current={moreActive ? "page" : undefined}
          data-tone="more"
          href={linkTo("/more")}
        >
          <span class="nav-icon" data-tone="more">
            {#if avatarUrl}
              <UserAvatar {avatarUrl} name={user.email} />
            {:else}
              <NavIcon name="more" />
            {/if}
          </span>
          <span>{t("More", locale)}</span>
        </a>
      </nav>
    </div>
  </div>
{/if}

<style>
  .rail-account {
    display: flex;
    box-sizing: border-box;
    width: 100%;
    min-height: 44px;
    align-items: center;
    gap: 0.65rem;
    padding: 0.35rem 0.5rem 0.35rem 12px;
    border: 0;
    border-radius: var(--radius-xs);
    color: var(--ink);
    background: transparent;
    font: inherit;
    text-align: left;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }

  .rail-account:hover {
    background: var(--accent-wash);
  }

  .rail-account:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .rail-account.active :global(.user-avatar) {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-wash);
  }

  .rail-account :global(.user-avatar) {
    flex-basis: 1.75rem;
    width: 1.75rem;
    height: 1.75rem;
    font-size: 0.8rem;
  }

  .rail-account-text {
    display: grid;
    min-width: 0;
  }

  .rail-account-title {
    font-weight: 700;
    line-height: 1.2;
  }

  .rail-account-email {
    overflow: hidden;
    color: var(--ink-muted);
    font-size: 0.75rem;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

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

  /* The lockup may shrink, but never paint over the language switch. */
  .public-tool-header > :global(.brand-header) {
    flex: 0 1 auto;
    min-width: 0;
    overflow: hidden;
  }

  .signup-route-main {
    display: flex;
    width: 100%;
    min-width: 0;
    justify-content: center;
  }

  .public-tool-main {
    width: min(100%, 56rem);
    margin-inline: auto;
    padding: clamp(1rem, 4vw, 2.5rem);
  }

  @media (max-width: 480px) {
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
  }
</style>
