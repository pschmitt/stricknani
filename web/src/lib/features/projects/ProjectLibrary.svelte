<script lang="ts">
  import EmptyArt from "#lib/components/EmptyArt.svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import LibraryFilters, {
    type ActiveFilterChip,
  } from "#lib/components/LibraryFilters.svelte";
  import CreateFab from "#lib/components/CreateFab.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import LibraryCard from "#lib/components/LibraryCard.svelte";
  import LibraryGrid from "#lib/components/LibraryGrid.svelte";
  import ScopeFilter from "#lib/components/ScopeFilter.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { scopeFor } from "#lib/library-scope.svelte.ts";
  import { foreignOwner } from "#lib/owner.ts";
  import { session } from "#lib/session.svelte.ts";
  import ImportDraftCards from "#lib/features/imports/ImportDraftCards.svelte";
  import {
    filtersFromSearch,
    listCategories,
    listProjects,
    setProjectFavorite,
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
  const scope = $derived(scopeFor(session.data?.user));
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
    const currentScope = scope;
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
    void listProjects(search.slice(1), controller.signal, {
      scope: currentScope,
    })
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

  let favoriteBusyIds = $state<number[]>([]);

  async function toggleFavorite(item: ProjectSummary) {
    const currentSession = session.data;
    if (!currentSession?.user || favoriteBusyIds.includes(item.id)) return;
    const favorite = !item.is_favorite;
    favoriteBusyIds = [...favoriteBusyIds, item.id];
    try {
      await setProjectFavorite(item.id, favorite, currentSession);
      items = items.map((entry) =>
        entry.id === item.id ? { ...entry, is_favorite: favorite } : entry,
      );
    } catch (error) {
      errorMessage =
        error instanceof Error
          ? error.message
          : t("The favorite could not be updated.", locale);
    } finally {
      favoriteBusyIds = favoriteBusyIds.filter((id) => id !== item.id);
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
    {#snippet actions()}
      <a
        class="ui-button ui-button--primary ui-button--md desktop-create"
        href={resolve("/projects/new")}
      >
        <ActionLabel icon="add">{t("New project", locale)}</ActionLabel>
      </a>
    {/snippet}
  </PageHeader>

  <ScopeFilter {locale} />

  <ImportDraftCards target="project" />

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
    <LibraryGrid busy>
      {#each [1, 2, 3] as skeleton (skeleton)}
        <div class="library-skeleton" aria-hidden="true">
          <span></span><i></i><i></i>
        </div>
      {/each}
    </LibraryGrid>
  {:else if loadState === "ready" && items.length === 0}
    <div class="empty-state" aria-live="polite">
      <EmptyArt icon="projects" tone="projects" />
      {#if hasFilters}
        <h2>{t("No projects match those filters.", locale)}</h2>
        <p>{t("Try a different name, category, or tag.", locale)}</p>
        <Button variant="secondary" icon="reset" onclick={clearFilters}>
          {t("Clear filters", locale)}
        </Button>
      {:else}
        <h2>{t("Nothing on the needles yet", locale)}</h2>
        <p>
          {t(
            "Start with one project to keep its pattern and progress together.",
            locale,
          )}
        </p>
      {/if}
    </div>
  {:else if loadState === "ready"}
    <LibraryGrid>
      {#each items as item (item.id)}
        <LibraryCard
          class="project-card"
          kind="project"
          href={`${resolve("/projects")}/${item.id}`}
          imageUrl={item.preview_url}
          name={item.name}
          meta={item.category}
          updated={`${t("Updated", locale)} ${formatDate(item.updated_at)}`}
          chips={item.tags}
          owner={foreignOwner(item.owner, userId)}
          favorite={item.is_favorite}
          favoriteBusy={favoriteBusyIds.includes(item.id)}
          {locale}
          onfavorite={() => toggleFavorite(item)}
        />
      {/each}
    </LibraryGrid>
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

  <CreateFab
    href={resolve("/projects/new")}
    label={t("New project", locale)}
    testId="create-fab-project"
  />
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
    border-radius: var(--radius-xs);
    padding: 0.75rem 0.9rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .notice-error p {
    margin: 0;
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

  .library-skeleton {
    display: grid;
    min-height: 72px;
    grid-template-columns: 64px minmax(0, 1fr);
    align-items: center;
    gap: 0.4rem 0.8rem;
    border-bottom: 1px solid var(--line);
  }

  .library-skeleton span {
    grid-row: span 2;
    width: 64px;
    aspect-ratio: 4 / 3;
    border-radius: 10px;
    background: var(--surface-raised);
  }

  .library-skeleton i {
    display: block;
    width: 70%;
    height: 0.7rem;
    border-radius: 999px;
    background: var(--surface-raised);
  }

  .library-skeleton i:last-child {
    width: 42%;
  }

  @container library-grid (min-width: 720px) {
    .library-skeleton {
      min-height: 0;
      grid-template-columns: 1fr;
      border: 1px solid var(--line);
      border-radius: 16px;
      padding-bottom: 1.1rem;
      overflow: hidden;
    }

    .library-skeleton span {
      grid-row: auto;
      width: 100%;
      border-radius: 0;
    }

    .library-skeleton i {
      margin-inline: 1.05rem;
    }
  }

  @media (max-width: 640px) {
    .desktop-create {
      display: none;
    }

    /* Room under the last card for the floating create button. */
    .library {
      padding-bottom: 5.5rem;
    }
  }
</style>
