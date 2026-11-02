<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { ApiError } from "#lib/api.ts";
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

<section class="library" aria-labelledby="library-title">
  <header class="library-heading">
    <div>
      <h1 id="library-title">{t("Projects", locale)}</h1>
      <p class="library-intro">
        {t("Find a project, pick up a row, and keep your notes close.", locale)}
      </p>
    </div>
    <a class="new-project" href={resolve("/projects/new")}>
      <span aria-hidden="true">+</span>
      {t("New project", locale)}
    </a>
  </header>

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
    <button class="search-button" type="submit">{t("Search", locale)}</button>
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
        }}>{t("Clear filters", locale)}</button
      >
    {/if}
  </form>

  {#if filters.tag}
    <div class="active-filter" aria-live="polite">
      <span>{t("Tag", locale)}: {filters.tag}</span>
      <button
        type="button"
        aria-label={t("Remove tag filter", locale)}
        onclick={() => applyFilters({ ...filters, tag: "" })}>×</button
      >
    </div>
  {/if}

  {#if errorMessage}
    <div class="notice-error" role="alert">
      <p>{errorMessage}</p>
      <button type="button" onclick={() => (reload += 1)}>
        {t("Try again", locale)}
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
          }}>{t("Clear filters", locale)}</button
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
          {t("Add a project", locale)}
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
          <a class="project-open" href={`${resolve("/projects")}/${item.id}`}>
            <div class="project-image">
              {#if item.preview_url}
                <img src={item.preview_url} alt="" loading="lazy" />
              {:else}
                <span class="image-placeholder" aria-hidden="true">✳</span>
              {/if}
              {#if item.category}
                <span class="category-pill">{item.category}</span>
              {/if}
            </div>
            <div class="project-copy">
              <h2>{item.name}</h2>
              <p class="updated-date">
                {t("Updated", locale)}
                {formatDate(item.updated_at)}
              </p>
            </div>
          </a>
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
                  >{tag}</button
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
          {t("Previous", locale)}
        </button>
        <span>{t("Page", locale)} {meta.page}</span>
        <button
          type="button"
          disabled={!meta.has_more}
          onclick={() => changePage(meta.page + 1)}
        >
          {t("Next", locale)}
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

  .library-heading {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 1rem;
    border-bottom: 1px solid var(--line);
    padding-bottom: 1.25rem;
  }

  .library-heading h1 {
    margin: 0 0 0.35rem;
    font-size: clamp(2rem, 5vw, 2.7rem);
    letter-spacing: -0.055em;
    line-height: 1.05;
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

  .new-project span {
    font-size: 1.2rem;
    line-height: 1;
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

  .project-open {
    display: block;
    min-width: 0;
    color: var(--ink);
    text-decoration: none;
  }

  .project-image {
    position: relative;
    display: grid;
    min-height: 9.5rem;
    aspect-ratio: 1.42;
    place-items: center;
    overflow: hidden;
    background:
      radial-gradient(
        ellipse at 65% 28%,
        color-mix(in srgb, var(--accent) 15%, transparent) 0 17%,
        transparent 18%
      ),
      linear-gradient(145deg, var(--surface-raised), var(--accent-wash));
  }

  .project-image img {
    width: 100%;
    height: 100%;
    position: absolute;
    inset: 0;
    object-fit: cover;
  }

  .image-placeholder {
    color: color-mix(in srgb, var(--accent) 54%, transparent);
    font-size: 2.7rem;
  }

  .category-pill {
    position: absolute;
    bottom: 0.65rem;
    left: 0.65rem;
    max-width: calc(100% - 4rem);
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.25rem 0.65rem;
    color: var(--ink);
    background: var(--surface);
    font-size: 0.78rem;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .project-copy {
    display: grid;
    gap: 0.35rem;
    padding: 0.9rem 1rem 1rem;
  }

  .project-copy h2 {
    overflow: hidden;
    margin: 0;
    font-size: 1.08rem;
    letter-spacing: -0.025em;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .updated-date {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.82rem;
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

    .library-heading {
      align-items: flex-start;
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

    .project-image {
      min-height: 7.8rem;
    }

    .project-copy {
      gap: 0.25rem;
      padding: 0.7rem;
    }

    .project-copy h2 {
      padding-right: 1.8rem;
      font-size: 0.96rem;
      white-space: normal;
    }

    .favorite-button {
      top: 0.4rem;
      right: 0.4rem;
      width: 44px;
      height: 44px;
    }
  }

  @media (max-width: 380px) {
    .library-heading {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
    }

    .library-heading > div {
      grid-column: 1 / -1;
    }

    .new-project {
      grid-column: 1 / -1;
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

    .project-image {
      aspect-ratio: 1.65;
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

  @container (min-width: 381px) and (max-width: 700px) {
    .library-heading {
      align-items: flex-start;
      flex-wrap: wrap;
    }

    .library-heading > div {
      min-width: 0;
      flex: 1 1 16rem;
    }

    .filters {
      grid-template-columns: minmax(0, 1fr) minmax(0, 0.7fr);
      align-items: end;
    }

    .search-field,
    .category-field,
    .search-field input,
    .category-field select {
      min-width: 0;
    }

    .favorite-filter {
      grid-column: 1 / -1;
    }

    .search-button {
      grid-column: 1;
      grid-row: auto;
    }

    .clear-button {
      grid-column: 2;
      grid-row: auto;
    }
  }
</style>
