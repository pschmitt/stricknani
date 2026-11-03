<script lang="ts">
  import EmptyArt from "#lib/components/EmptyArt.svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import { page } from "$app/state";
  import Button from "#lib/components/Button.svelte";
  import LibraryFilters, {
    type ActiveFilterChip,
  } from "#lib/components/LibraryFilters.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    listYarns,
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
  const hasFilters = $derived(Boolean(filters.query || filters.favorite));
  const activeFilterCount = $derived(Number(filters.favorite));
  const activeFilterChips = $derived.by<ActiveFilterChip[]>(() =>
    filters.favorite
      ? [
          {
            key: "favorite",
            label: t("Favorites only", locale),
            remove: () => applyFilters({ ...filters, favorite: false }),
          },
        ]
      : [],
  );

  $effect(() => {
    const nextFilters = yarnFiltersFromSearch(page.url.search);
    filters = nextFilters;
  });

  $effect(() => {
    const search = page.url.search;
    const currentUserId = userId;
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
    void listYarns(search.slice(1), controller.signal)
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
        <ActionLabel icon="create">{t("Add yarn", locale)}</ActionLabel>
      </a>
    {/snippet}
  </PageHeader>

  <LibraryFilters
    query={filters.query}
    queryLabel={t("Search your yarn", locale)}
    placeholder={t("Name, brand, colorway, or fiber", locale)}
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
      <label class="favorite-filter">
        <input
          type="checkbox"
          checked={filters.favorite}
          onchange={(event) =>
            applyFilters({ ...filters, favorite: event.currentTarget.checked })}
        />
        <span>{t("Favorites only", locale)}</span>
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
    <div class="yarn-grid" aria-busy="true" aria-live="polite">
      {#each [1, 2, 3] as skeleton (skeleton)}
        <div class="yarn-skeleton" aria-hidden="true">
          <span></span><i></i><i></i>
        </div>
      {/each}
    </div>
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
        <a
          class="ui-button ui-button--secondary ui-button--md"
          href={resolve("/yarns/new")}
        >
          <ActionLabel icon="create"
            >{t("Add your first yarn", locale)}</ActionLabel
          >
        </a>
      {/if}
    </section>
  {:else if loadState === "ready"}
    <div class="yarn-grid" aria-live="polite">
      {#each items as item (item.id)}
        <article class="yarn-card yarn-row">
          <ResourceCard
            kind="yarn"
            href={`${resolve("/yarns")}/${item.id}`}
            imageUrl={item.preview_url}
            name={item.name}
            subtitle={[
              item.brand,
              item.colorway,
              item.weight_category,
              formatDate(item.updated_at),
            ]
              .filter(Boolean)
              .join(" · ") || t("Details to add", locale)}
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
    border: 1px solid color-mix(in srgb, var(--danger) 35%, var(--surface));
    border-radius: 0.8rem;
    padding: 0.75rem 0.9rem;
    color: var(--danger);
    background: var(--surface);
  }

  .notice-error p {
    margin: 0;
  }

  .yarn-grid {
    display: grid;
    gap: 0;
    min-width: 0;
  }

  .yarn-row {
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

  .yarn-skeleton {
    display: grid;
    min-height: 72px;
    grid-template-columns: 56px minmax(0, 1fr);
    align-items: center;
    gap: 0.75rem;
    border-bottom: 1px solid var(--line);
  }

  .yarn-skeleton span {
    width: 56px;
    height: 56px;
    border-radius: 0.5rem;
    background: var(--surface-raised);
  }

  .yarn-skeleton i {
    width: 70%;
    height: 0.7rem;
    border-radius: 999px;
    background: var(--surface-raised);
  }

  @media (max-width: 640px) {
    .desktop-create {
      display: none;
    }
  }
</style>
