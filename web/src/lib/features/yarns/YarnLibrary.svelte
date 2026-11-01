<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { ApiError } from "#lib/api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
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
  let busyFavorite = $state<number | null>(null);
  let reload = $state(0);

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const hasFilters = $derived(Boolean(filters.query || filters.favorite));

  $effect(() => {
    const search = page.url.search;
    const currentUserId = userId;
    reload;
    const controller = new AbortController();
    const nextFilters = yarnFiltersFromSearch(search);
    filters = nextFilters;
    searchText = nextFilters.query;
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

  function applyFilters(next: YarnFilters) {
    const search = yarnSearchFromFilters({ ...next, page: 1 });
    void goto(`${resolve("/yarns")}${search ? `?${search}` : ""}`, {
      reset: false,
    });
  }

  function changePage(nextPage: number) {
    const search = yarnSearchFromFilters({ ...filters, page: nextPage });
    void goto(`${resolve("/yarns")}${search ? `?${search}` : ""}`, {
      reset: false,
    });
  }

  async function toggleFavorite(event: MouseEvent, item: YarnSummary) {
    event.preventDefault();
    event.stopPropagation();
    const currentSession = session.data;
    if (!currentSession?.user || busyFavorite !== null) return;
    busyFavorite = item.id;
    errorMessage = "";
    try {
      await setYarnFavorite(item.id, !item.is_favorite, currentSession);
      if (filters.favorite && item.is_favorite) reload += 1;
      else
        items = items.map((entry) =>
          entry.id === item.id
            ? { ...entry, is_favorite: !entry.is_favorite }
            : entry,
        );
    } catch (error) {
      errorMessage =
        error instanceof ApiError && error.status === 404
          ? t("This yarn is no longer available.", locale)
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
  <title>Stricknani · {t("Yarns", locale)}</title>
  <meta
    name="description"
    content={t("Browse the yarns connected to your projects.", locale)}
  />
</svelte:head>

<section class="library" aria-labelledby="yarn-library-title">
  <header class="library-heading">
    <div>
      <p class="section-mark">{t("Your materials", locale)}</p>
      <h1 id="yarn-library-title">{t("Yarns", locale)}</h1>
      <p class="intro">
        {t(
          "Keep colors, fiber details, and project links close at hand.",
          locale,
        )}
      </p>
    </div>
    <a class="primary-action" href={resolve("/yarns/new")}>
      <span aria-hidden="true">+</span>{t("Add yarn", locale)}
    </a>
  </header>

  <form
    class="filters"
    role="search"
    aria-label={t("Search yarn", locale)}
    onsubmit={(event) => {
      event.preventDefault();
      applyFilters({ ...filters, query: searchText.trim() });
    }}
  >
    <label class="search-field">
      <span>{t("Search your yarn", locale)}</span>
      <input
        type="search"
        bind:value={searchText}
        placeholder={t("Name, brand, colorway, or fiber", locale)}
        autocomplete="off"
      />
    </label>
    <label class="favorite-filter">
      <input
        type="checkbox"
        checked={filters.favorite}
        onchange={(event) =>
          applyFilters({ ...filters, favorite: event.currentTarget.checked })}
      />
      <span>{t("Favorites only", locale)}</span>
    </label>
    <button class="search-button" type="submit">{t("Search", locale)}</button>
    {#if hasFilters}
      <button
        class="clear-button"
        type="button"
        onclick={() => {
          searchText = "";
          applyFilters({ query: "", favorite: false, page: 1 });
        }}>{t("Clear filters", locale)}</button
      >
    {/if}
  </form>

  {#if errorMessage}
    <div class="notice-error" role="alert">
      <p>{errorMessage}</p>
      <button type="button" onclick={() => (reload += 1)}>
        {t("Try again", locale)}
      </button>
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
      <span class="empty-mark" aria-hidden="true">◌</span>
      {#if hasFilters}
        <h2>{t("No yarn matches those filters.", locale)}</h2>
        <p>{t("Try another search or show all yarn.", locale)}</p>
        <button
          class="secondary-action"
          type="button"
          onclick={() => {
            searchText = "";
            applyFilters({ query: "", favorite: false, page: 1 });
          }}>{t("Clear filters", locale)}</button
        >
      {:else}
        <h2>{t("Your yarn shelf is ready.", locale)}</h2>
        <p>
          {t(
            "Add a skein to keep its details and project links together.",
            locale,
          )}
        </p>
        <a class="secondary-action" href={resolve("/yarns/new")}
          >{t("Add your first yarn", locale)}</a
        >
      {/if}
    </section>
  {:else if loadState === "ready"}
    <div class="results-heading">
      <p>
        {#if filters.query}
          {t("Results for", locale)} <strong>“{filters.query}”</strong>
        {:else if filters.favorite}
          {t("Your yarn favorites", locale)}
        {:else}
          {t("Your yarn", locale)}
        {/if}
      </p>
      <span>{t("Page", locale)} {meta.page}</span>
    </div>
    <div class="yarn-grid" aria-live="polite">
      {#each items as item (item.id)}
        <article class="yarn-card">
          <a class="yarn-open" href={`${resolve("/yarns")}/${item.id}`}>
            <div class="yarn-image">
              {#if item.preview_url}
                <img src={item.preview_url} alt="" loading="lazy" />
              {:else}
                <span class="skein-mark" aria-hidden="true">◌</span>
                <span class="image-label">{t("Photo not added", locale)}</span>
              {/if}
            </div>
            <div class="yarn-copy">
              <h2>{item.name}</h2>
              <p class="yarn-identity">
                {[item.brand, item.colorway].filter(Boolean).join(" · ") ||
                  t("Details to add", locale)}
              </p>
              <div class="yarn-meta">
                {#if item.weight_category}<span>{item.weight_category}</span
                  >{/if}
                <time datetime={item.updated_at}
                  >{formatDate(item.updated_at)}</time
                >
              </div>
            </div>
          </a>
          <button
            class="favorite-button"
            class:is-favorite={item.is_favorite}
            type="button"
            aria-label={item.is_favorite
              ? t("Remove from favorites", locale)
              : t("Add to favorites", locale)}
            aria-pressed={item.is_favorite}
            disabled={busyFavorite !== null}
            onclick={(event) => void toggleFavorite(event, item)}
          >
            <span aria-hidden="true">{item.is_favorite ? "★" : "☆"}</span>
          </button>
        </article>
      {/each}
    </div>
    <nav class="pagination" aria-label={t("Yarn pages", locale)}>
      <button
        type="button"
        disabled={meta.page <= 1}
        onclick={() => changePage(meta.page - 1)}
        >{t("Previous", locale)}</button
      >
      <span>{t("Page", locale)} {meta.page}</span>
      <button
        type="button"
        disabled={!meta.has_more}
        onclick={() => changePage(meta.page + 1)}>{t("Next", locale)}</button
      >
    </nav>
  {/if}
</section>

<style>
  .library {
    display: grid;
    gap: 1.25rem;
    max-width: 76rem;
    margin: 0 auto;
  }

  .library-heading {
    display: flex;
    align-items: end;
    justify-content: space-between;
    gap: 1.25rem;
  }

  .section-mark {
    margin: 0;
    color: var(--accent-strong);
    font-size: 0.85rem;
    font-weight: 650;
  }

  h1 {
    margin: 0.3rem 0 0.35rem;
    font-size: clamp(2rem, 5vw, 2.7rem);
    letter-spacing: -0.055em;
    line-height: 1.05;
  }

  .intro {
    max-width: 37rem;
    margin: 0;
    color: var(--ink-muted);
  }

  .primary-action,
  .search-button,
  .secondary-action {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    gap: 0.45rem;
    border: 0;
    border-radius: 0.7rem;
    padding: 0.6rem 0.95rem;
    color: #fff;
    background: var(--accent-strong);
    font: inherit;
    font-weight: 650;
    text-decoration: none;
    cursor: pointer;
  }

  .primary-action span {
    font-size: 1.2rem;
    line-height: 1;
  }

  .filters {
    display: grid;
    grid-template-columns: minmax(12rem, 1fr) auto auto auto;
    align-items: end;
    gap: 0.75rem;
    border: 1px solid var(--line);
    border-radius: 0.95rem;
    padding: 0.85rem;
    background: var(--surface);
  }

  .search-field {
    display: grid;
    gap: 0.35rem;
    color: var(--ink-muted);
    font-size: 0.82rem;
    font-weight: 620;
  }

  .search-field input {
    width: 100%;
    min-height: 44px;
    border: 1px solid var(--line);
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
    font-size: 0.9rem;
    white-space: nowrap;
    cursor: pointer;
  }

  .favorite-filter input {
    width: 1.15rem;
    height: 1.15rem;
    accent-color: var(--accent-strong);
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
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 14rem), 1fr));
    gap: 1rem;
  }

  .yarn-card {
    position: relative;
    min-width: 0;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 0.95rem;
    background: var(--surface);
    transition:
      border-color 150ms ease,
      transform 150ms ease;
  }

  .yarn-card:hover {
    transform: translateY(-2px);
    border-color: var(--accent);
  }

  .yarn-open {
    display: block;
    min-width: 0;
    color: inherit;
    text-decoration: none;
  }

  .yarn-image {
    position: relative;
    display: grid;
    min-height: 10rem;
    aspect-ratio: 1.36;
    place-items: center;
    overflow: hidden;
    background: var(--surface-raised);
  }

  .yarn-image img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .skein-mark {
    color: var(--accent);
    font-size: 3rem;
    line-height: 1;
  }

  .image-label {
    position: absolute;
    bottom: 0.7rem;
    left: 0.75rem;
    color: var(--ink-muted);
    font-size: 0.8rem;
  }

  .yarn-copy {
    display: grid;
    gap: 0.35rem;
    padding: 0.9rem 1rem 1rem;
  }

  .yarn-copy h2 {
    overflow: hidden;
    margin: 0;
    padding-right: 2rem;
    font-size: 1.08rem;
    letter-spacing: -0.025em;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .yarn-identity {
    overflow: hidden;
    margin: 0;
    color: var(--ink-muted);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .yarn-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.45rem;
    padding-top: 0.2rem;
    color: var(--ink-muted);
    font-size: 0.82rem;
  }

  .yarn-meta span {
    border-radius: 999px;
    padding: 0.2rem 0.5rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-weight: 620;
  }

  .yarn-meta time {
    margin-left: auto;
  }

  .favorite-button {
    position: absolute;
    top: 0.55rem;
    right: 0.55rem;
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    border: 1px solid var(--line);
    border-radius: 50%;
    color: var(--ink-muted);
    background: var(--surface);
    font-size: 1.3rem;
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
  }

  .pagination button:disabled {
    color: var(--ink-muted);
    cursor: default;
  }

  .empty-state {
    display: grid;
    justify-items: center;
    gap: 0.5rem;
    border: 1px dashed var(--line);
    border-radius: 1rem;
    padding: clamp(1.5rem, 6vw, 3rem) 1rem;
    text-align: center;
  }

  .empty-mark {
    color: var(--accent);
    font-size: 2rem;
  }

  .empty-state h2,
  .empty-state p {
    margin: 0;
  }

  .empty-state p {
    max-width: 32rem;
    color: var(--ink-muted);
  }

  .yarn-skeleton {
    display: grid;
    gap: 0.75rem;
    min-height: 17rem;
    border: 1px solid var(--line);
    border-radius: 0.95rem;
    padding-bottom: 1rem;
    background: var(--surface);
  }

  .yarn-skeleton span {
    min-height: 10rem;
    background: var(--surface-raised);
  }

  .yarn-skeleton i {
    height: 0.75rem;
    margin: 0 1rem;
    border-radius: 999px;
    background: var(--surface-raised);
  }

  @media (max-width: 640px) {
    .library-heading {
      align-items: start;
      flex-direction: column;
    }

    .primary-action {
      width: 100%;
    }

    .filters {
      grid-template-columns: minmax(0, 1fr) auto;
    }

    .search-field {
      grid-column: 1 / -1;
    }

    .search-button,
    .clear-button {
      width: 100%;
    }

    .clear-button {
      grid-column: 1 / -1;
    }

    .yarn-grid {
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 12rem), 1fr));
    }
  }

  @media (max-width: 380px) {
    .yarn-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
