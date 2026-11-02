<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import LibraryFilters, {
    type ActiveFilterChip,
  } from "#lib/components/LibraryFilters.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    filtersFromSearch,
    listCategories,
    listProjects,
    searchFromFilters,
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
  let reload = $state(0);

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const hasFilters = $derived(
    Boolean(
      filters.query || filters.category || filters.tag || filters.favorite,
    ),
  );
  const activeFilterCount = $derived(
    Number(Boolean(filters.category)) +
      Number(Boolean(filters.tag)) +
      Number(filters.favorite),
  );
  const activeFilterChips = $derived.by<ActiveFilterChip[]>(() => [
    ...(filters.category
      ? [
          {
            key: "category",
            label: `${t("Category", locale)}: ${filters.category}`,
            remove: () => applyFilters({ ...filters, category: "" }),
          },
        ]
      : []),
    ...(filters.tag
      ? [
          {
            key: "tag",
            label: `${t("Tag", locale)}: ${filters.tag}`,
            remove: () => applyFilters({ ...filters, tag: "" }),
          },
        ]
      : []),
    ...(filters.favorite
      ? [
          {
            key: "favorite",
            label: t("Favorites", locale),
            remove: () => applyFilters({ ...filters, favorite: false }),
          },
        ]
      : []),
  ]);
  const categories = $derived(
    [
      ...new Set([
        ...categoryNames,
        ...items.flatMap((item) => (item.category ? [item.category] : [])),
      ]),
    ].sort((left, right) => left.localeCompare(right, locale)),
  );

  $effect(() => {
    const nextFilters = filtersFromSearch(page.url.search);
    filters = nextFilters;
  });

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

  function applyFilters(next: ProjectFilters, replaceState = false) {
    const search = searchFromFilters({
      ...next,
      query: searchText.trim(),
      page: 1,
    });
    void goto(`${resolve("/projects")}${search ? `?${search}` : ""}`, {
      replace: replaceState,
      reset: false,
    });
  }

  function applyQuery(query: string) {
    searchText = query;
    applyFilters({ ...filters, query }, true);
  }

  function clearFilters() {
    searchText = "";
    applyFilters({
      query: "",
      category: "",
      tag: "",
      favorite: false,
      page: 1,
    });
  }

  function changePage(nextPage: number) {
    const query = searchText.trim();
    const search = searchFromFilters({
      ...filters,
      query,
      page: query !== filters.query ? 1 : nextPage,
    });
    void goto(`${resolve("/projects")}${search ? `?${search}` : ""}`, {
      reset: false,
    });
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
    {#snippet actions()}
      <a
        class="ui-button ui-button--primary ui-button--md desktop-create"
        href={resolve("/projects/new")}
      >
        <ActionLabel icon="create">{t("New project", locale)}</ActionLabel>
      </a>
    {/snippet}
  </PageHeader>

  <LibraryFilters
    query={filters.query}
    queryLabel={t("Search your projects", locale)}
    placeholder={t("Name, notes, yarn, or tag", locale)}
    filterLabel={t("Filter", locale)}
    closeLabel={t("Close filters", locale)}
    clearLabel={t("Clear filters", locale)}
    removeFilterLabel={t("Remove filter", locale)}
    {activeFilterCount}
    chips={activeFilterChips}
    onquerydraft={(query) => (searchText = query)}
    onquerycommit={applyQuery}
    onclear={clearFilters}
  >
    {#snippet filterControls()}
      <div class="panel-field">
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
      <div class="panel-field">
        <label for={`${idPrefix}-tag`}>{t("Tag", locale)}</label>
        <input
          id={`${idPrefix}-tag`}
          type="search"
          value={filters.tag}
          autocomplete="off"
          onchange={(event) =>
            applyFilters({ ...filters, tag: event.currentTarget.value.trim() })}
        />
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
    {/snippet}
  </LibraryFilters>

  {#if errorMessage}
    <div class="notice-error" role="alert">
      <p>{errorMessage}</p>
      <Button icon="reset" onclick={() => (reload += 1)}>
        {t("Try again", locale)}
      </Button>
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
        <Button variant="secondary" icon="reset" onclick={clearFilters}>
          {t("Clear filters", locale)}
        </Button>
      {:else}
        <h2>{t("Your project library is ready.", locale)}</h2>
        <p>
          {t(
            "Start with one project to keep its pattern and progress together.",
            locale,
          )}
        </p>
        <a
          class="ui-button ui-button--secondary ui-button--md"
          href={resolve("/projects/new")}
        >
          <ActionLabel icon="create">{t("Add a project", locale)}</ActionLabel>
        </a>
      {/if}
    </div>
  {:else if loadState === "ready"}
    <div class="project-grid" aria-live="polite">
      {#each items as item (item.id)}
        <article class="project-card project-row">
          <ResourceCard
            kind="project"
            href={`${resolve("/projects")}/${item.id}`}
            imageUrl={item.preview_url}
            name={item.name}
            subtitle={[
              item.category,
              `${t("Updated", locale)} ${formatDate(item.updated_at)}`,
            ]
              .filter(Boolean)
              .join(" · ")}
            variant="library-row"
            titleTag="h2"
          />
          {#if item.is_favorite}
            <span
              class="favorite-indicator"
              role="img"
              aria-label={t("Favorite", locale)}
              title={t("Favorite", locale)}>★</span
            >
          {/if}
        </article>
      {/each}
    </div>
    {#if meta.page > 1 || meta.has_more}
      <nav class="pagination" aria-label={t("Project pages", locale)}>
        <Button
          variant="secondary"
          icon="previous"
          disabled={meta.page <= 1}
          onclick={() => changePage(meta.page - 1)}
        >
          {t("Previous", locale)}
        </Button>
        <span>{t("Page", locale)} {meta.page}</span>
        <Button
          variant="secondary"
          icon="next"
          disabled={!meta.has_more}
          onclick={() => changePage(meta.page + 1)}
        >
          {t("Next", locale)}
        </Button>
      </nav>
    {/if}
  {/if}
</section>

<style>
  .library {
    display: grid;
    gap: 0.85rem;
    min-width: 0;
    container-type: inline-size;
  }

  .desktop-create {
    min-height: 44px;
  }

  .panel-field {
    display: grid;
    min-width: 0;
    gap: 0.35rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
    font-weight: 620;
  }

  .panel-field input,
  .panel-field select {
    width: 100%;
    min-width: 0;
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
    gap: 0.6rem;
    color: var(--ink);
    cursor: pointer;
  }

  .favorite-filter input {
    width: 1.2rem;
    height: 1.2rem;
    accent-color: var(--accent-strong);
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
    gap: 0;
    min-width: 0;
  }

  .project-row {
    display: grid;
    min-width: 0;
    min-height: 72px;
    grid-template-columns: minmax(0, 1fr) 24px;
    align-items: center;
    gap: 0.35rem;
    border-bottom: 1px solid var(--line);
    padding: 0.45rem 0.25rem;
  }

  .favorite-indicator {
    justify-self: center;
    color: var(--accent-strong);
    font-size: 1rem;
  }

  .pagination {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0.75rem;
    padding-block: 0.4rem;
  }

  .pagination span {
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .empty-state {
    display: grid;
    justify-items: center;
    gap: 0.45rem;
    border: 1px dashed var(--line);
    border-radius: 1rem;
    padding: clamp(1.5rem, 6vw, 3rem) 1rem;
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
    margin: 0.4rem 0 0;
    font-size: 1.2rem;
  }

  .empty-state p {
    max-width: 34rem;
    margin: 0 0 0.45rem;
    color: var(--ink-muted);
  }

  .project-skeleton {
    display: grid;
    min-height: 72px;
    grid-template-columns: 56px minmax(0, 1fr);
    align-items: center;
    gap: 0.75rem;
    border-bottom: 1px solid var(--line);
  }

  .project-skeleton span,
  .project-skeleton i {
    display: block;
    border-radius: 0.5rem;
    background: var(--surface-raised);
  }

  .project-skeleton span {
    width: 56px;
    height: 56px;
  }

  .project-skeleton i {
    width: 70%;
    height: 0.7rem;
  }

  .project-skeleton i:last-child {
    width: 42%;
  }

  @media (max-width: 640px) {
    .desktop-create {
      display: none;
    }
  }
</style>
