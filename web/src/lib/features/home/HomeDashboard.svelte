<script lang="ts">
  import { onMount, tick } from "svelte";
  import { resolve } from "$app/paths";
  import {
    appCardMode,
    isStandaloneDisplay,
    readAppCardDismissed,
    writeAppCardDismissed,
  } from "#lib/app-links.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import GetAppCard from "#lib/components/GetAppCard.svelte";
  import Button from "#lib/components/Button.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import OwnerBadge from "#lib/components/OwnerBadge.svelte";
  import ScopeFilter from "#lib/components/ScopeFilter.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { scopeFor } from "#lib/library-scope.svelte.ts";
  import { foreignOwner } from "#lib/owner.ts";
  import { session } from "#lib/session.svelte.ts";
  import ImportDraftCards from "#lib/features/imports/ImportDraftCards.svelte";
  import {
    loadFavoriteProjects,
    loadFavoriteYarns,
    loadOngoingProjects,
    loadRecentProjects,
  } from "./home-api";
  import { setHero } from "#lib/components/brand-handoff.svelte.ts";
  import { greetingFor, visitGreetingIndex } from "./greeting";
  import { homeSection } from "./home-section.svelte";
  import ActivityFeed from "./ActivityFeed.svelte";
  import ContinueCarousel from "./ContinueCarousel.svelte";

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const owner = () => userId;

  // The scope is read synchronously inside each loader, so a change of the Mine/Everyone choice
  // reloads every section.
  const scope = () => scopeFor(session.data?.user);
  const favoriteProjects = homeSection(owner, (signal) =>
    loadFavoriteProjects(signal, scope()),
  );
  const favoriteYarns = homeSection(owner, (signal) =>
    loadFavoriteYarns(signal, scope()),
  );
  const recentProjects = homeSection(owner, (signal) =>
    loadRecentProjects(signal, scope()),
  );
  const ongoingProjects = homeSection(owner, (signal) =>
    loadOngoingProjects(signal, scope()),
  );

  const favoritesLoaded = $derived(
    favoriteProjects.status !== "loading" && favoriteYarns.status !== "loading",
  );
  const hasFavorites = $derived(
    favoriteProjects.items.length > 0 || favoriteYarns.items.length > 0,
  );

  // "Get the app" card: browser-only facts, so it stays hidden until mounted.
  let appCard = $state<"hidden" | "prominent" | "compact">("hidden");
  onMount(() => {
    appCard = appCardMode({
      userAgent: navigator.userAgent,
      standalone: isStandaloneDisplay(),
      dismissed: readAppCardDismissed(),
    });
  });

  // The header brand morphs this mascot into its logo slot as the page
  // scrolls (see BrandHeader).
  let heroEl = $state<HTMLElement>();
  onMount(() => {
    if (!heroEl) return;
    setHero(heroEl);
    return () => setHero(null);
  });

  // The dismissal is remembered as the exit starts; the card leaves the page once
  // it has flown off and the space below has collapsed.
  function dismissAppCard() {
    writeAppCardDismissed();
  }

  let dashboardEl = $state<HTMLElement>();
  async function appCardGone() {
    appCard = "hidden";
    await tick();
    // The card held focus; hand it to the page so keyboard users keep their place.
    dashboardEl?.focus({ preventScroll: true });
  }

  // A playful greeting for the time of day; the pick is made once per visit, so
  // re-renders and theme switches keep it.
  const greeting = $derived(
    t(greetingFor(new Date().getHours(), visitGreetingIndex()), locale),
  );

  const relative = $derived(
    new Intl.RelativeTimeFormat(locale, { numeric: "auto" }),
  );

  /** "today", "yesterday", "3 days ago", "2 weeks ago"... */
  function updatedAgo(value: string): string {
    const then = new Date(value);
    if (Number.isNaN(then.valueOf())) return "";
    const days = Math.round(
      (startOfDay(then) - startOfDay(new Date())) / 86_400_000,
    );
    if (days > -14) return relative.format(days, "day");
    if (days > -60) return relative.format(Math.round(days / 7), "week");
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(
      then,
    );
  }

  function startOfDay(date: Date): number {
    return new Date(
      date.getFullYear(),
      date.getMonth(),
      date.getDate(),
    ).valueOf();
  }
</script>

<svelte:head>
  <title>Stricknani · {t("Home", locale)}</title>
  <meta
    name="description"
    content={t("Your favorite projects and yarns, together.", locale)}
  />
</svelte:head>

<section
  bind:this={dashboardEl}
  tabindex="-1"
  class="home-dashboard page-container page-container--wide"
  aria-labelledby="home-title"
>
  <header class="home-greeting">
    <span class="home-greeting__mascot" bind:this={heroEl}
      ><Mascot size={88} bob={true} /></span
    >
    <div class="home-greeting__copy">
      <h1 id="home-title" class="home-greeting__title">{greeting}</h1>
      <p>{t("What are we knitting today?", locale)}</p>
    </div>
    {#if userId !== null}
      <div class="home-greeting__menu">
        <ScopeFilter {locale} variant="menu" />
      </div>
    {/if}
  </header>

  {#if session.status === "loading"}
    <p class="home-status" role="status" aria-live="polite">
      <Mascot size={48} roll={true} reactive={false} />
      {t("Loading your home…", locale)}
    </p>
  {:else if userId === null}
    <div class="home-signed-out">
      <p>{t("Sign in to see your favorite projects and yarns.", locale)}</p>
      <a
        class="ui-button ui-button--primary ui-button--md"
        href={resolve("/login")}
      >
        <ActionLabel icon="sign-in">{t("Sign in", locale)}</ActionLabel>
      </a>
    </div>
  {:else}
    <ImportDraftCards target="all" />

    <!-- 1. Pick up where you left off: the projects in progress, swipeable. -->
    <ContinueCarousel
      status={ongoingProjects.status}
      items={ongoingProjects.items}
      {locale}
      {updatedAgo}
      retry={ongoingProjects.retry}
    />

    <!-- 2. Favourites as photo tiles, or one hint while there are none. -->
    {#if favoritesLoaded && !hasFavorites && favoriteProjects.status === "ready" && favoriteYarns.status === "ready"}
      <p class="home-hint">
        <NavIcon name="favorite" />
        <span>{t("Tap ♥ on a project or yarn to keep it here.", locale)}</span>
      </p>
    {/if}
    {#each [{ id: "favorite-projects", title: t("Favorite projects", locale), section: favoriteProjects, kind: "project" as const, icon: "favorite" as const, href: `${resolve("/projects")}?favorite=true`, error: t("Favorite projects could not be loaded.", locale) }, { id: "favorite-yarns", title: t("Favorite yarns", locale), section: favoriteYarns, kind: "yarn" as const, icon: "yarns" as const, href: `${resolve("/yarns")}?favorite=true`, error: t("Favorite yarns could not be loaded.", locale) }] as group (group.id)}
      {#if group.section.status === "error"}
        <div class="home-error" role="alert">
          <p>{group.error}</p>
          <Button icon="reset" onclick={group.section.retry}>
            {t("Try again", locale)}
          </Button>
        </div>
      {:else if group.section.items.length > 0}
        <section class="home-section" aria-labelledby={`${group.id}-title`}>
          <div class="home-section__heading">
            <h2 id={`${group.id}-title`} class="home-heading">
              <span
                class="heading-badge"
                data-tone={group.kind === "project" ? "home" : "yarns"}
                ><NavIcon name={group.icon} /></span
              >{group.title}
            </h2>
            <a class="see-all" href={group.href}
              >{t("See all", locale)}<NavIcon name="next" /></a
            >
          </div>
          <ul class="tile-grid" aria-label={group.title}>
            {#each group.section.items as item, index (item.id)}
              <li>
                <a
                  class="tile"
                  data-tint={(index + (group.kind === "yarn" ? 1 : 0)) % 3}
                  href={group.kind === "project"
                    ? resolve("/projects/[id]", { id: String(item.id) })
                    : resolve("/yarns/[id]", { id: String(item.id) })}
                >
                  <span class="tile__image">
                    {#if item.preview_url}
                      <img src={item.preview_url} alt="" loading="lazy" />
                    {:else}
                      <NavIcon
                        name={group.kind === "project" ? "projects" : "yarns"}
                      />
                    {/if}
                  </span>
                  {#if foreignOwner(item.owner, userId)}
                    {@const foreign = foreignOwner(item.owner, userId)}
                    {#if foreign}
                      <span class="owner-corner"
                        ><OwnerBadge owner={foreign} {locale} compact /></span
                      >
                    {/if}
                  {/if}
                  <span class="tile__name">{item.name}</span>
                </a>
              </li>
            {/each}
          </ul>
        </section>
      {/if}
    {/each}

    <!-- 3. Recently updated projects, compact. -->
    {#if recentProjects.items.length > 0}
      <section class="home-section" aria-labelledby="recent-projects-title">
        <div class="home-section__heading">
          <h2 id="recent-projects-title" class="home-heading">
            <span class="heading-badge" data-tone="projects"
              ><NavIcon name="projects" /></span
            >{t("Recent projects", locale)}
          </h2>
          <a class="see-all" href={resolve("/projects")}
            >{t("See all", locale)}<NavIcon name="next" /></a
          >
        </div>
        <ul class="recent-list" aria-label={t("Recent projects", locale)}>
          {#each recentProjects.items as item (item.id)}
            <li>
              <ResourceCard
                kind="project"
                href={resolve("/projects/[id]", { id: String(item.id) })}
                imageUrl={item.preview_url}
                name={item.name}
                owner={foreignOwner(item.owner, userId)}
                subtitle={[item.category, updatedAgo(item.updated_at)]
                  .filter(Boolean)
                  .join(" · ")}
                titleTag="h3"
              />
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    <!-- 4. What changed lately, newest first. -->
    <ActivityFeed ownerId={userId} {locale}>
      {#snippet seeAll()}
        <a class="see-all" href={resolve("/activity")}
          >{t("See all", locale)}<NavIcon name="next" /></a
        >
      {/snippet}
    </ActivityFeed>

    {#if appCard !== "hidden"}
      <GetAppCard
        {locale}
        variant={appCard}
        ondismiss={dismissAppCard}
        ongone={appCardGone}
      />
    {/if}
  {/if}
</section>

<style>
  .owner-corner {
    position: absolute;
    inset-block-start: 0.4rem;
    inset-inline-start: 0.4rem;
    display: flex;
    max-width: calc(100% - 0.8rem);
  }

  .home-dashboard:focus {
    outline: none;
  }

  .home-dashboard {
    display: grid;
    min-width: 0;
    gap: 1.75rem;
    padding-top: 0.5rem;
  }

  .home-greeting {
    display: flex;
    align-items: center;
    gap: 0.9rem;
  }
  .home-greeting__menu {
    align-self: flex-start;
    margin-inline-start: auto;
  }

  .home-greeting__mascot {
    display: inline-flex;
    flex: none;
  }
  .home-greeting__copy h1 {
    margin: 0;
    font-size: clamp(1.9rem, 1.4rem + 2vw, 2.7rem);
    line-height: 1.1;
    letter-spacing: -0.01em;
  }
  .home-greeting__title {
    animation: greeting-pop 420ms cubic-bezier(0.34, 1.56, 0.64, 1) both;
    transform-origin: left center;
  }
  @keyframes greeting-pop {
    from {
      opacity: 0;
      transform: scale(0.92) rotate(-1.5deg);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }
  .home-greeting__copy p {
    margin: 0.15rem 0 0;
    color: var(--ink-muted);
    font-size: 1.1rem;
  }

  .home-heading {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    margin: 0;
    font-size: 1.35rem;
    line-height: 1.3;
  }
  .heading-badge {
    --tone: var(--tone-home);
    --tone-wash: var(--tone-home-wash);
    display: grid;
    width: 1.9rem;
    height: 1.9rem;
    flex: none;
    place-items: center;
    border-radius: 50%;
    color: var(--tone);
    background: var(--tone-wash);
  }
  .heading-badge[data-tone="yarns"] {
    --tone: var(--tone-yarns);
    --tone-wash: var(--tone-yarns-wash);
  }
  .heading-badge[data-tone="projects"] {
    --tone: var(--tone-projects);
    --tone-wash: var(--tone-projects-wash);
  }
  .heading-badge :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }

  .home-section {
    display: grid;
    min-width: 0;
    gap: 0.7rem;
  }

  .home-section__heading {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .see-all {
    display: inline-flex;
    flex: none;
    align-items: center;
    gap: 0.15rem;
    min-height: 44px;
    color: var(--accent-strong);
    padding-inline: 0.5rem;
    font-size: 0.95rem;
    font-weight: 800;
    text-decoration: none;
  }
  .see-all:hover,
  .see-all:focus-visible {
    text-decoration: underline;
    text-underline-offset: 0.18em;
  }
  .see-all :global(svg) {
    width: 1rem;
    height: 1rem;
  }

  .tile-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
    gap: 0.9rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .tile {
    position: relative;
    display: grid;
    gap: 0.45rem;
    color: var(--ink);
    text-decoration: none;
  }
  .tile__image {
    display: grid;
    aspect-ratio: 1;
    place-items: center;
    overflow: hidden;
    border-radius: var(--radius-md);
    color: var(--on-primary-container);
    background: var(--primary-container);
    transition: transform 200ms cubic-bezier(0.3, 1.6, 0.5, 1);
  }
  .tile[data-tint="1"] .tile__image {
    color: var(--on-lagoon-container);
    background: var(--lagoon-container);
  }
  .tile[data-tint="2"] .tile__image {
    color: var(--on-marigold-container);
    background: var(--marigold-container);
  }
  .tile:hover .tile__image {
    transform: translateY(-3px) rotate(-1.5deg);
  }
  .tile:active .tile__image {
    transform: scale(0.96);
  }
  .tile__image img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .tile__image :global(svg) {
    width: 2rem;
    height: 2rem;
  }
  .tile__name {
    overflow: hidden;
    font-size: 0.95rem;
    font-weight: 800;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .recent-list {
    display: grid;
    gap: 0.55rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  @media (prefers-reduced-motion: no-preference) {
    .recent-list :global(.resource-card__image) {
      transition: transform 200ms cubic-bezier(0.3, 1.6, 0.5, 1);
    }
    .recent-list :global(a.resource-card__link:hover .resource-card__image),
    .recent-list
      :global(a.resource-card__link:focus-visible .resource-card__image) {
      transform: translateY(-3px) rotate(-1.5deg);
    }
  }

  .home-hint {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    margin: 0;
    border-radius: var(--radius-xs);
    padding: 0.75rem 1rem;
    color: var(--ink-muted);
    background: var(--surface);
    font-size: 0.92rem;
  }
  .home-hint :global(svg) {
    flex: none;
    width: 1.1rem;
    height: 1.1rem;
    color: var(--accent-strong);
  }

  .home-status {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin: 0;
    color: var(--ink-muted);
    font-weight: 700;
  }
  .home-error,
  .home-signed-out {
    display: flex;
    min-width: 0;
    align-items: center;
    flex-wrap: wrap;
    gap: 0.7rem;
  }
  .home-error p,
  .home-signed-out p {
    margin: 0;
  }

  @media (max-width: 560px) {
    .home-greeting :global(.mascot) {
      --mascot-size: 4.5rem;
    }
  }

  @media (min-width: 900px) {
    .recent-list {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .home-greeting__title {
      animation: none;
    }
    .tile__image {
      transition: none;
    }
    .tile:hover .tile__image {
      transform: none;
    }
  }
</style>
