<script lang="ts">
  import EmptyArt from "#lib/components/EmptyArt.svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import { page } from "$app/state";
  import Button from "#lib/components/Button.svelte";
  import LibrarySearch from "#lib/components/LibrarySearch.svelte";
  import LibraryCard from "#lib/components/LibraryCard.svelte";
  import LibraryGrid from "#lib/components/LibraryGrid.svelte";
  import CreateFab from "#lib/components/CreateFab.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { scopeFor, setScope } from "#lib/library-scope.svelte.ts";
  import { foreignOwner } from "#lib/owner.ts";
  import { session } from "#lib/session.svelte.ts";
  import ImportDraftCards from "#lib/features/imports/ImportDraftCards.svelte";
  import {
    listYarns,
    setYarnFavorite,
    yarnFiltersFromSearch,
    yarnSearchFromFilters,
    type YarnFilters,
    type YarnSummary,
  } from "./yarn-api";

  type LoadState = "loading" | "ready" | "error";

  let loadState = $state<LoadState>("loading");
  let items = $state<YarnSummary[]>([]);
  let meta = $state({ page: 1, per_page: 50, has_more: false });
  let filters = $state<YarnFilters>({ query: "", favorite: false, page: 1 });
  let searchText = $state("");
  let errorMessage = $state("");
  let reload = $state(0);

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const scope = $derived(scopeFor(session.data?.user));
  const scopeUser = $derived(session.data?.user ?? null);
  const scopeIsMine = $derived(
    Boolean(scopeUser?.is_admin) && scope === "mine",
  );

  function resetScope() {
    if (scopeUser) setScope(scopeUser, "all");
  }
  const hasFilters = $derived(
    Boolean(filters.query || filters.favorite || scopeIsMine),
  );

  $effect(() => {
    const nextFilters = yarnFiltersFromSearch(page.url.search);
    filters = nextFilters;
  });

  $effect(() => {
    const search = page.url.search;
    const currentUserId = userId;
    const currentScope = scope;
    reload;
    const controller = new AbortController();
    items = [];
    if (currentUserId === null) {
      loadState = "error";
      errorMessage = t("Sign in to view your yarn.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    errorMessage = "";
    void listYarns(search.slice(1), controller.signal, {
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
            : t("The yarn list could not be loaded.", locale);
      });
    return () => controller.abort();
  });

  function applyFilters(next: YarnFilters, replaceState = false) {
    const search = yarnSearchFromFilters({
      ...next,
      query: searchText.trim(),
      page: 1,
    });
    void goto(`${resolve("/yarns")}${search ? `?${search}` : ""}`, {
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
    resetScope();
    applyFilters({ query: "", favorite: false, page: 1 });
  }

  function changePage(nextPage: number) {
    const query = searchText.trim();
    const search = yarnSearchFromFilters({
      ...filters,
      query,
      page: query !== filters.query ? 1 : nextPage,
    });
    void goto(`${resolve("/yarns")}${search ? `?${search}` : ""}`, {
      reset: false,
    });
  }

  let favoriteBusyIds = $state<number[]>([]);

  async function toggleFavorite(item: YarnSummary) {
    const currentSession = session.data;
    if (!currentSession?.user || favoriteBusyIds.includes(item.id)) return;
    const favorite = !item.is_favorite;
    favoriteBusyIds = [...favoriteBusyIds, item.id];
    try {
      await setYarnFavorite(item.id, favorite, currentSession);
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
  <title>Stricknani · {t("Yarns", locale)}</title>
  <meta
    name="description"
    content={t("Browse the yarns connected to your projects.", locale)}
  />
</svelte:head>

<section
  class="library page-container page-container--wide"
  aria-labelledby="yarn-library-title"
>
  <PageHeader title={t("Yarns", locale)} titleId="yarn-library-title">
    {#snippet actions()}
      <a
        class="ui-button ui-button--primary ui-button--md desktop-create"
        href={resolve("/yarns/new")}
      >
        <ActionLabel icon="add">{t("Add yarn", locale)}</ActionLabel>
      </a>
    {/snippet}
  </PageHeader>

  <ImportDraftCards target="yarn" />

  <LibrarySearch
    query={filters.query}
    queryLabel={t("Search your yarn", locale)}
    placeholder={t("Name, brand, colorway, or fiber", locale)}
    removeFilterLabel={t("Remove filter", locale)}
    onquerydraft={(query) => (searchText = query)}
    onquerycommit={applyQuery}
  />

  <div
    class="filter-pills"
    role="group"
    aria-label={t("Filter", locale)}
    data-testid="yarn-filter-pills"
  >
    <button
      class="filter-pill"
      class:filter-pill--selected={!filters.favorite && !scopeIsMine}
      type="button"
      aria-pressed={!filters.favorite && !scopeIsMine}
      data-testid="yarn-filter-all"
      onclick={() => {
        applyFilters({ ...filters, favorite: false });
        resetScope();
      }}
    >
      {t("All", locale)}
    </button>
    <button
      class="filter-pill"
      class:filter-pill--selected={filters.favorite}
      type="button"
      aria-pressed={filters.favorite}
      data-testid="yarn-filter-favorites"
      onclick={() => applyFilters({ ...filters, favorite: !filters.favorite })}
    >
      {t("Favorites only", locale)}
    </button>
    {#if scopeUser?.is_admin}
      <button
        class="filter-pill"
        class:filter-pill--selected={scopeIsMine}
        type="button"
        aria-pressed={scopeIsMine}
        data-testid="yarn-filter-mine"
        onclick={() =>
          scopeUser && setScope(scopeUser, scopeIsMine ? "all" : "mine")}
      >
        {t("Mine", locale)}
      </button>
    {/if}
  </div>

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
    <section class="empty-state" aria-live="polite">
      <EmptyArt icon="yarns" tone="yarns" />
      {#if hasFilters}
        <h2>{t("No yarn matches those filters.", locale)}</h2>
        <p>{t("Try another search or show all yarn.", locale)}</p>
        <Button variant="secondary" icon="reset" onclick={clearFilters}>
          {t("Clear filters", locale)}
        </Button>
      {:else}
        <h2>{t("Your stash is empty", locale)}</h2>
        <p>
          {t(
            "Add a skein to keep its details and project links together.",
            locale,
          )}
        </p>
      {/if}
    </section>
  {:else if loadState === "ready"}
    <LibraryGrid>
      {#each items as item (item.id)}
        <LibraryCard
          class="yarn-card"
          kind="yarn"
          href={`${resolve("/yarns")}/${item.id}`}
          imageUrl={item.preview_url}
          name={item.name}
          meta={[item.brand, item.colorway].filter(Boolean).join(" · ") ||
            t("Details to add", locale)}
          updated={`${t("Updated", locale)} ${formatDate(item.updated_at)}`}
          chips={item.weight_category ? [item.weight_category] : []}
          owner={foreignOwner(item.owner, userId)}
          favorite={item.is_favorite}
          favoriteBusy={favoriteBusyIds.includes(item.id)}
          {locale}
          onfavorite={() => toggleFavorite(item)}
        />
      {/each}
    </LibraryGrid>
    {#if meta.page > 1 || meta.has_more}
      <nav class="pagination" aria-label={t("Yarn pages", locale)}>
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
    href={resolve("/yarns/new")}
    label={t("New yarn", locale)}
    testId="create-fab-yarn"
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

  .filter-pills {
    display: flex;
    min-width: 0;
    gap: 0.5rem;
    overflow-x: auto;
    padding: 0.15rem 0 0.35rem;
    overscroll-behavior-inline: contain;
    scrollbar-width: thin;
  }

  .filter-pill {
    display: inline-flex;
    min-height: 44px;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.45rem 0.85rem;
    color: var(--ink);
    background: var(--surface);
    font: inherit;
    font-size: 0.9rem;
    cursor: pointer;
  }

  .filter-pill:hover {
    border-color: var(--accent-strong);
  }

  .filter-pill:focus-visible {
    outline: 3px solid color-mix(in srgb, var(--accent-strong) 45%, transparent);
    outline-offset: 2px;
  }

  .filter-pill--selected {
    border-color: var(--primary-container);
    color: var(--on-primary-container);
    background: var(--primary-container);
    font-weight: 650;
  }

  .notice-error {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.8rem;
    border: 1px solid color-mix(in srgb, var(--danger) 35%, var(--surface));
    border-radius: var(--radius-xs);
    padding: 0.75rem 0.9rem;
    color: var(--danger);
    background: var(--surface);
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
