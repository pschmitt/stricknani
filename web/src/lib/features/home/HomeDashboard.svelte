<script lang="ts">
  import { resolve } from "$app/paths";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    loadFavoriteProjects,
    loadFavoriteYarns,
    loadRecentProjects,
  } from "./home-api";
  import { homeSection } from "./home-section.svelte";

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const owner = () => userId;

  const favoriteProjects = homeSection(owner, loadFavoriteProjects);
  const favoriteYarns = homeSection(owner, loadFavoriteYarns);
  const recentProjects = homeSection(owner, loadRecentProjects);

  // The project worked on last gets the large card; the list below shows the rest.
  const current = $derived(recentProjects.items[0] ?? null);
  const otherRecent = $derived(recentProjects.items.slice(1));
  const favoritesLoaded = $derived(
    favoriteProjects.status !== "loading" && favoriteYarns.status !== "loading",
  );
  const hasFavorites = $derived(
    favoriteProjects.items.length > 0 || favoriteYarns.items.length > 0,
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
  class="home-dashboard page-container page-container--wide"
  aria-labelledby="home-title"
>
  <!-- The navigation already says where we are; the heading stays for screen readers. -->
  <h1 id="home-title" class="visually-hidden">{t("Home", locale)}</h1>

  {#if session.status === "loading"}
    <p class="home-status" role="status" aria-live="polite">
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
    <!-- 1. Pick up the project worked on last. -->
    <section class="home-continue" aria-labelledby="home-continue-title">
      <h2 id="home-continue-title" class="home-heading">
        {t("Continue knitting", locale)}
      </h2>
      {#if recentProjects.status === "loading"}
        <div
          class="continue-card continue-card--placeholder"
          aria-hidden="true"
        >
          <span class="continue-card__image"></span>
          <span class="continue-card__copy">
            <span class="placeholder-line"></span>
            <span class="placeholder-line placeholder-line--short"></span>
          </span>
        </div>
        <p class="visually-hidden" role="status">
          {t("Loading recent projects…", locale)}
        </p>
      {:else if recentProjects.status === "error"}
        <div class="home-error" role="alert">
          <p>{t("Recently updated projects could not be loaded.", locale)}</p>
          <Button icon="reset" onclick={recentProjects.retry}>
            {t("Try again", locale)}
          </Button>
        </div>
      {:else if current}
        <a
          class="continue-card"
          href={resolve("/projects/[id]", { id: String(current.id) })}
        >
          <span class="continue-card__image">
            {#if current.preview_url}
              <img src={current.preview_url} alt="" loading="eager" />
            {:else}
              <NavIcon name="projects" />
            {/if}
          </span>
          <span class="continue-card__copy">
            <span class="continue-card__name">{current.name}</span>
            <span class="continue-card__meta">
              {#if current.category}<span>{current.category}</span>{/if}
              <span
                >{t("Updated", locale)} {updatedAgo(current.updated_at)}</span
              >
            </span>
          </span>
          <span class="continue-card__go" aria-hidden="true">
            <NavIcon name="next" />
          </span>
        </a>
      {:else}
        <div class="home-first-run">
          <p>{t("No projects yet.", locale)}</p>
          <a
            class="ui-button ui-button--primary ui-button--md"
            href={resolve("/create")}
          >
            <ActionLabel icon="create"
              >{t("Create your first project", locale)}</ActionLabel
            >
          </a>
        </div>
      {/if}
    </section>

    <!-- 2. Favourites as photo tiles, or one hint while there are none. -->
    {#if favoritesLoaded && !hasFavorites && favoriteProjects.status === "ready" && favoriteYarns.status === "ready"}
      <p class="home-hint">
        <NavIcon name="favorite" />
        <span>{t("Tap ♥ on a project or yarn to keep it here.", locale)}</span>
      </p>
    {/if}
    {#each [{ id: "favorite-projects", title: t("Favorite projects", locale), section: favoriteProjects, kind: "project" as const, href: `${resolve("/projects")}?favorite=true`, error: t("Favorite projects could not be loaded.", locale) }, { id: "favorite-yarns", title: t("Favorite yarns", locale), section: favoriteYarns, kind: "yarn" as const, href: `${resolve("/yarns")}?favorite=true`, error: t("Favorite yarns could not be loaded.", locale) }] as group (group.id)}
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
            <h2 id={`${group.id}-title`} class="home-heading">{group.title}</h2>
            <a class="see-all" href={group.href}
              >{t("See all", locale)}<NavIcon name="next" /></a
            >
          </div>
          <ul class="tile-grid" aria-label={group.title}>
            {#each group.section.items as item (item.id)}
              <li>
                <a
                  class="tile"
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
                  <span class="tile__name">{item.name}</span>
                </a>
              </li>
            {/each}
          </ul>
        </section>
      {/if}
    {/each}

    <!-- 3. The rest of the recent projects, compact. -->
    {#if otherRecent.length > 0}
      <section class="home-section" aria-labelledby="recent-projects-title">
        <div class="home-section__heading">
          <h2 id="recent-projects-title" class="home-heading">
            {t("Recent projects", locale)}
          </h2>
          <a class="see-all" href={resolve("/projects")}
            >{t("See all", locale)}<NavIcon name="next" /></a
          >
        </div>
        <ul class="recent-list" aria-label={t("Recent projects", locale)}>
          {#each otherRecent as item (item.id)}
            <li>
              <ResourceCard
                kind="project"
                href={resolve("/projects/[id]", { id: String(item.id) })}
                imageUrl={item.preview_url}
                name={item.name}
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
  {/if}
</section>

<style>
  .home-dashboard {
    display: grid;
    min-width: 0;
    gap: 1.75rem;
    padding-top: 0.5rem;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }

  .home-heading {
    margin: 0;
    font-size: 1.05rem;
    line-height: 1.3;
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
    font-size: 0.92rem;
    font-weight: 620;
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

  /* The one prominent element: the project to get back to. */
  .home-continue {
    display: grid;
    gap: 0.7rem;
  }
  .continue-card {
    display: grid;
    grid-template-columns: 9rem minmax(0, 1fr) auto;
    align-items: center;
    gap: 1.1rem;
    border: 1px solid var(--line);
    border-radius: 1.1rem;
    padding: 0.75rem 1rem 0.75rem 0.75rem;
    color: var(--ink);
    background: var(--surface);
    text-decoration: none;
    transition:
      border-color 140ms ease,
      transform 140ms ease;
  }
  a.continue-card:hover {
    border-color: var(--accent-strong);
  }
  a.continue-card:active {
    transform: scale(0.99);
  }
  .continue-card__image {
    display: grid;
    aspect-ratio: 1;
    place-items: center;
    overflow: hidden;
    border-radius: 0.8rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
  }
  .continue-card__image img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .continue-card__image :global(svg) {
    width: 2.5rem;
    height: 2.5rem;
  }
  .continue-card__copy {
    display: grid;
    min-width: 0;
    gap: 0.4rem;
  }
  .continue-card__name {
    font-size: clamp(1.25rem, 1rem + 1vw, 1.6rem);
    font-weight: 750;
    line-height: 1.2;
    letter-spacing: -0.015em;
    overflow-wrap: anywhere;
  }
  .continue-card__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.2rem 0.8rem;
    color: var(--ink-muted);
    font-size: 0.92rem;
  }
  .continue-card__go {
    display: grid;
    place-items: center;
    width: 2.5rem;
    height: 2.5rem;
    border-radius: 999px;
    color: var(--accent-strong);
    background: var(--accent-wash);
  }
  .continue-card__go :global(svg) {
    width: 1.2rem;
    height: 1.2rem;
  }
  .continue-card--placeholder .continue-card__image,
  .placeholder-line {
    background: var(--surface-raised);
  }
  .placeholder-line {
    display: block;
    width: 60%;
    height: 1.2rem;
    border-radius: 0.4rem;
  }
  .placeholder-line--short {
    width: 35%;
    height: 0.9rem;
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
    border-radius: 0.85rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    transition: transform 140ms ease;
  }
  .tile:hover .tile__image {
    transform: translateY(-2px);
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
    font-size: 0.92rem;
    font-weight: 650;
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

  .home-hint {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    margin: 0;
    border-radius: 0.8rem;
    padding: 0.75rem 0.9rem;
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
    margin: 0;
    color: var(--ink-muted);
  }
  .home-error,
  .home-signed-out,
  .home-first-run {
    display: flex;
    min-width: 0;
    align-items: center;
    flex-wrap: wrap;
    gap: 0.7rem;
  }
  .home-error p,
  .home-signed-out p,
  .home-first-run p {
    margin: 0;
  }

  @media (min-width: 900px) {
    .recent-list {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 600px) {
    .continue-card {
      grid-template-columns: 6.5rem minmax(0, 1fr);
      gap: 0.9rem;
    }
    .continue-card__go {
      display: none;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .continue-card,
    .tile__image {
      transition: none;
    }
    a.continue-card:active,
    .tile:hover .tile__image {
      transform: none;
    }
  }
</style>
