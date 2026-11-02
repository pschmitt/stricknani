<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    filtersFromSearch,
    listCategories,
    listProjects,
    searchFromFilters,
    setProjectFavorite,
    type ProjectFilters,
    type ProjectSummary,
  } from "./project-api";

  type LoadState = "loading" | "ready" | "error";

  const idPrefix = $props.id();
  const categorySelectId = `${idPrefix}-category`;

  let loadState = $state<LoadState>("loading");
  let items = $state<ProjectSummary[]>([]);
  let categoryNames = $state<string[]>([]);
  let meta = $state({ page: 1, per_page: 50, has_more: false });
  let filters = $state<ProjectFilters>(filtersFromSearch(""));
  let searchText = $state("");
  let errorMessage = $state("");
  let busyFavorite = $state<number | null>(null);
  let reload = $state(0);

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const hasFilters = $derived(
    Boolean(
      filters.query || filters.category || filters.tag || filters.favorite,
    ),
  );
  const categories = $derived(
    [
      ...new Set([
        ...categoryNames,
        ...items.flatMap((item) => (item.category ? [item.category] : [])),
      ]),
    ].sort((left, right) => left.localeCompare(right, locale)),
  );

  $effect(() => {
    const search = page.url.search;
    const currentUserId = userId;
    reload;
    const controller = new AbortController();
    items = [];
    categoryNames = [];
    if (currentUserId === null) {
      loadState = "error";
      errorMessage = t("Sign in to view your projects.", locale);
      return () => controller.abort();
    }
    const nextFilters = filtersFromSearch(search);
    filters = nextFilters;
    searchText = nextFilters.query;
    loadState = "loading";
    errorMessage = "";
    void listProjects(search.slice(1), controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        items = result.data;
        meta = result.meta;
        loadState = "ready";
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        loadState = "error";
        errorMessage =
          error instanceof Error
            ? error.message
            : t("The project list could not be loaded.", locale);
      });
    void listCategories(controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        categoryNames = result.map((category) => category.name);
      })
      .catch(() => {
        if (!controller.signal.aborted) categoryNames = [];
      });
    return () => controller.abort();
  });

  function applyFilters(next: ProjectFilters) {
    const search = searchFromFilters({ ...next, page: 1 });
    void goto(`${resolve("/projects")}${search ? `?${search}` : ""}`, {
      reset: false,
    });
  }

  function changePage(nextPage: number) {
    const search = searchFromFilters({ ...filters, page: nextPage });
    void goto(`${resolve("/projects")}${search ? `?${search}` : ""}`, {
      reset: false,
    });
  }

  async function toggleFavorite(event: MouseEvent, item: ProjectSummary) {
    event.preventDefault();
    event.stopPropagation();
    const currentSession = session.data;
    if (!currentSession?.user || busyFavorite !== null) return;
    busyFavorite = item.id;
    errorMessage = "";
    try {
      await setProjectFavorite(item.id, !item.is_favorite, currentSession);
      reload += 1;
    } catch (error) {
      errorMessage =
        error instanceof ApiError && error.status === 404
          ? t("This project is no longer available.", locale)
          : error instanceof Error
            ? error.message
            : t("The favorite could not be updated.", locale);
    } finally {
      busyFavorite = null;
    }
  }

  function formatDate(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.valueOf())) return "";
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(
      date,
    );
  }
</script>

<svelte:head>
  <title>Stricknani · {t("Projects", locale)}</title>
  <meta
    name="description"
    content={t("Browse and find your knitting projects.", locale)}
  />
</svelte:head>

<section
  class="library page-container page-container--wide"
  aria-labelledby="library-title"
>
  <PageHeader title={t("Projects", locale)} titleId="library-title">
    {#snippet context()}
      <p class="library-intro">
        {t("Find a project, pick up a row, and keep your notes close.", locale)}
      </p>
    {/snippet}
    {#snippet actions()}
      <a class="new-project" href={resolve("/projects/new")}>
        <ActionLabel icon="create">{t("New project", locale)}</ActionLabel>
      </a>
    {/snippet}
  </PageHeader>

  <form
    class="filters"
    role="search"
    aria-label={t("Search projects", locale)}
    onsubmit={(event) => {
      event.preventDefault();
      applyFilters({ ...filters, query: searchText.trim() });
    }}
  >
    <label class="search-field">
      <span>{t("Search your projects", locale)}</span>
      <input
        type="search"
        bind:value={searchText}
        placeholder={t("Name, notes, yarn, or tag", locale)}
        autocomplete="off"
      />
    </label>
    <div class="category-field">
      <label for={categorySelectId}>{t("Category", locale)}</label>
      <select
        id={categorySelectId}
        value={filters.category}
        onchange={(event) =>
          applyFilters({ ...filters, category: event.currentTarget.value })}
      >
        <option value="">{t("All categories", locale)}</option>
        {#if filters.category && !categories.includes(filters.category)}
          <option value={filters.category}>{filters.category}</option>
        {/if}
        {#each categories as category (category)}
          <option value={category}>{category}</option>
        {/each}
      </select>
    </div>
    <label class="favorite-filter">
      <input
        type="checkbox"
        checked={filters.favorite}
        onchange={(event) =>
          applyFilters({ ...filters, favorite: event.currentTarget.checked })}
      />
      <span>{t("Favorites", locale)}</span>
    </label>
    <button class="search-button" type="submit">
      <ActionLabel icon="search">
        <span class="search-button-label">{t("Search", locale)}</span>
      </ActionLabel>
    </button>
    {#if hasFilters}
      <button
        class="clear-button"
        type="button"
        onclick={() => {
          searchText = "";
          applyFilters({
            query: "",
            category: "",
            tag: "",
            favorite: false,
            page: 1,
          });
        }}
        ><ActionLabel icon="reset">{t("Clear filters", locale)}</ActionLabel
        ></button
      >
    {/if}
  </form>

  {#if filters.tag}
    <div class="active-filter" aria-live="polite">
      <span>{t("Tag", locale)}: {filters.tag}</span>
      <button
        type="button"
        aria-label={t("Remove tag filter", locale)}
        onclick={() => applyFilters({ ...filters, tag: "" })}
        ><NavIcon name="close" /></button
      >
    </div>
  {/if}

  {#if errorMessage}
    <div class="notice-error" role="alert">
      <p>{errorMessage}</p>
      <button type="button" onclick={() => (reload += 1)}>
        <ActionLabel icon="reset">{t("Try again", locale)}</ActionLabel>
      </button>
    </div>
  {/if}

  {#if loadState === "loading"}
    <div class="project-grid loading-grid" aria-busy="true" aria-live="polite">
      {#each [1, 2, 3] as skeleton (skeleton)}
        <div class="project-skeleton" aria-hidden="true">
          <span></span><i></i><i></i>
        </div>
      {/each}
    </div>
  {:else if loadState === "ready" && items.length === 0}
    <div class="empty-state" aria-live="polite">
      <span class="empty-mark" aria-hidden="true">✳</span>
      {#if hasFilters}
        <h2>{t("No projects match those filters.", locale)}</h2>
        <p>{t("Try a different name, category, or tag.", locale)}</p>
        <button
          class="secondary-button"
          type="button"
          onclick={() => {
            searchText = "";
            applyFilters({
              query: "",
              category: "",
              tag: "",
              favorite: false,
              page: 1,
            });
          }}
          ><ActionLabel icon="reset">{t("Clear filters", locale)}</ActionLabel
          ></button
        >
      {:else}
        <h2>{t("Your project library is ready.", locale)}</h2>
        <p>
          {t(
            "Start with one project to keep its pattern and progress together.",
            locale,
          )}
        </p>
        <a class="secondary-button" href={resolve("/projects/new")}>
          <ActionLabel icon="create">{t("Add a project", locale)}</ActionLabel>
        </a>
      {/if}
    </div>
  {:else if loadState === "ready"}
    <div class="results-heading">
      <p>
        {#if filters.query}
          {t("Results for", locale)} <strong>“{filters.query}”</strong>
        {:else if filters.favorite}
          {t("Your favorites", locale)}
        {:else}
          {t("Your projects", locale)}
        {/if}
      </p>
      <span>{t("Page", locale)} {meta.page}</span>
    </div>
    <div class="project-grid" aria-live="polite">
      {#each items as item (item.id)}
        <article class="project-card">
          <ResourceCard
            kind="project"
            href={`${resolve("/projects")}/${item.id}`}
            imageUrl={item.preview_url}
            name={item.name}
            badge={item.category}
            subtitle={`${t("Updated", locale)} ${formatDate(item.updated_at)}`}
            variant="library"
            titleTag="h2"
          />
          {#if item.tags.length}
            <div
              class="tag-list"
              role="group"
              aria-label={t("Project tags", locale)}
            >
              {#each item.tags as tag, index (`${index}:${tag}`)}
                <button
                  type="button"
                  onclick={() => applyFilters({ ...filters, tag })}
                  ><ActionLabel icon="categories">{tag}</ActionLabel></button
                >
              {/each}
            </div>
          {/if}
          <button
            class="favorite-button"
            class:is-favorite={item.is_favorite}
            type="button"
            disabled={busyFavorite !== null}
            aria-pressed={item.is_favorite}
            aria-label={item.is_favorite
              ? t("Remove from favorites", locale)
              : t("Add to favorites", locale)}
            onclick={(event) => toggleFavorite(event, item)}
          >
            <span aria-hidden="true">{item.is_favorite ? "★" : "☆"}</span>
          </button>
        </article>
      {/each}
    </div>
    {#if meta.page > 1 || meta.has_more}
      <nav class="pagination" aria-label={t("Project pages", locale)}>
        <button
          type="button"
          disabled={meta.page <= 1}
          onclick={() => changePage(meta.page - 1)}
        >
          <ActionLabel icon="previous">{t("Previous", locale)}</ActionLabel>
        </button>
        <span>{t("Page", locale)} {meta.page}</span>
        <button
          type="button"
          disabled={!meta.has_more}
          onclick={() => changePage(meta.page + 1)}
        >
          <ActionLabel icon="next">{t("Next", locale)}</ActionLabel>
        </button>
      </nav>
    {/if}
  {/if}
</section>

<style>
  .library {
    display: grid;
    gap: 1.5rem;
    min-width: 0;
    container-type: inline-size;
  }

  .library-intro {
    max-width: 36rem;
    margin: 0;
    color: var(--ink-muted);
  }

  .new-project,
  .search-button,
  .secondary-button {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    gap: 0.45rem;
    border: 0;
    border-radius: 0.7rem;
    padding: 0.65rem 0.95rem;
    color: var(--on-accent);
    background: var(--accent-strong);
    font: inherit;
    font-weight: 650;
    text-decoration: none;
    cursor: pointer;
  }

  .filters {
    display: grid;
    grid-template-columns:
      minmax(13rem, 1fr) minmax(10rem, 0.55fr)
      auto auto auto;
    align-items: end;
    gap: 0.75rem;
    border: 1px solid var(--line);
    border-radius: 0.95rem;
    padding: 0.85rem;
    background: var(--surface);
  }

  .search-field,
  .category-field {
    display: grid;
    gap: 0.35rem;
    color: var(--ink-muted);
    font-size: 0.82rem;
    font-weight: 620;
  }

  .search-field input,
  .category-field select {
    width: 100%;
    min-height: 44px;
    border: 1px solid var(--control-border);
    border-radius: 0.65rem;
    padding: 0.55rem 0.7rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
  }

  .favorite-filter {
    display: flex;
    min-height: 44px;
    align-items: center;
    gap: 0.5rem;
    color: var(--ink);
    font-size: 0.9rem;
    white-space: nowrap;
    cursor: pointer;
  }

  .favorite-filter input {
    width: 1.15rem;
    height: 1.15rem;
    accent-color: var(--accent-strong);
  }

  .search-button {
    min-width: 5.5rem;
  }

  .clear-button,
  .notice-error button,
  .pagination button {
    min-height: 44px;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.55rem 0.75rem;
    color: var(--accent-strong);
    background: var(--surface);
    font: inherit;
    font-weight: 620;
    cursor: pointer;
  }

  .active-filter {
    display: inline-flex;
    width: fit-content;
    min-height: 44px;
    align-items: center;
    gap: 0.45rem;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.2rem 0.3rem 0.2rem 0.85rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 0.9rem;
  }

  .active-filter button {
    width: 44px;
    height: 44px;
    border: 0;
    border-radius: 50%;
    color: inherit;
    background: var(--surface);
    font-size: 1.2rem;
    cursor: pointer;
  }

  .notice-error {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.8rem;
    border: 1px solid color-mix(in srgb, var(--danger) 35%, transparent);
    border-radius: 0.8rem;
    padding: 0.75rem 0.9rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .notice-error p {
    margin: 0;
  }

  .project-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 15rem), 1fr));
    gap: 1rem;
  }

  .project-card {
    position: relative;
    min-width: 0;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 1rem;
    background: var(--surface);
    box-shadow: 0 5px 16px rgb(24 48 45 / 4%);
    transition:
      transform 150ms ease,
      box-shadow 150ms ease;
  }

  .project-card:hover {
    transform: translateY(-2px);
    box-shadow: var(--shadow);
  }

  .tag-list {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    padding: 0 1rem 1rem;
  }

  .tag-list button {
    min-height: 44px;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.15rem 0.55rem;
    color: var(--ink-muted);
    background: var(--surface-raised);
    font: inherit;
    font-size: 0.76rem;
    cursor: pointer;
  }

  .favorite-button {
    position: absolute;
    top: 0.6rem;
    right: 0.6rem;
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    border: 1px solid var(--line);
    border-radius: 50%;
    color: var(--ink-muted);
    background: var(--surface);
    font-size: 1.35rem;
    cursor: pointer;
  }

  .favorite-button.is-favorite {
    color: var(--accent-strong);
    background: var(--accent-wash);
  }

  .results-heading,
  .pagination {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
  }

  .results-heading p {
    margin: 0;
    font-weight: 620;
  }

  .results-heading span,
  .pagination span {
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .pagination {
    justify-content: center;
    padding-top: 0.4rem;
  }

  .pagination button:disabled {
    color: var(--ink-muted);
    cursor: default;
  }

  .empty-state {
    display: grid;
    justify-items: center;
    gap: 0.45rem;
    border: 1px dashed var(--line);
    border-radius: 1rem;
    padding: clamp(2rem, 8vw, 4rem) 1rem;
    background: var(--surface);
    text-align: center;
  }

  .empty-mark {
    display: grid;
    width: 3.5rem;
    aspect-ratio: 1;
    place-items: center;
    border-radius: 1rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 1.8rem;
  }

  .empty-state h2 {
    margin: 0.7rem 0 0;
    font-size: 1.28rem;
  }

  .empty-state p {
    max-width: 34rem;
    margin: 0 0 0.7rem;
    color: var(--ink-muted);
  }

  .project-skeleton {
    display: grid;
    gap: 0.8rem;
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: 0.7rem;
    background: var(--surface);
  }

  .project-skeleton span,
  .project-skeleton i {
    display: block;
    border-radius: 0.6rem;
    background: var(--surface-raised);
  }

  .project-skeleton span {
    aspect-ratio: 1.55;
  }

  .project-skeleton i {
    width: 70%;
    height: 0.9rem;
  }

  .project-skeleton i:last-child {
    width: 42%;
  }

  @media (max-width: 760px) {
    .library {
      gap: 1.15rem;
    }

    .new-project {
      flex: 0 0 auto;
      padding-inline: 0.75rem;
      font-size: 0.9rem;
    }

    .filters {
      grid-template-columns: minmax(0, 1fr) minmax(8rem, 0.65fr);
      align-items: end;
    }

    .favorite-filter {
      grid-column: 1;
    }

    .search-button,
    .clear-button {
      grid-row: 2;
    }

    .clear-button {
      grid-column: 2;
    }

    .project-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.7rem;
    }

    .favorite-button {
      top: 0.4rem;
      right: 0.4rem;
      width: 44px;
      height: 44px;
    }
  }

  @media (max-width: 380px) {
    .new-project {
      justify-self: start;
    }

    .filters {
      grid-template-columns: minmax(0, 1fr);
    }

    .favorite-filter,
    .search-button,
    .clear-button {
      grid-column: 1;
      grid-row: auto;
    }

    .project-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .project-card {
      transition: none;
    }

    .project-card:hover {
      transform: none;
    }
  }

  @container (max-width: 700px) {
    .filters {
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: end;
    }

    .search-field,
    .category-field,
    .search-field input,
    .category-field select {
      min-width: 0;
    }

    .search-field {
      grid-column: 1 / -1;
      grid-row: 1;
    }

    .search-field input {
      width: calc(100% - 56px);
    }

    .search-button {
      grid-column: 2;
      grid-row: 1;
      justify-self: end;
      width: 44px;
      min-width: 44px;
      min-height: 44px;
      padding-inline: 0;
    }

    .search-button-label {
      position: absolute;
      width: 1px;
      height: 1px;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      clip-path: inset(50%);
      white-space: nowrap;
    }

    .category-field {
      grid-column: 1;
      grid-row: 2;
    }

    .favorite-filter {
      grid-column: 2;
      grid-row: 2;
    }

    .clear-button {
      grid-column: 1 / -1;
      grid-row: 3;
      width: 100%;
      min-height: 44px;
      justify-content: center;
    }
  }
</style>
