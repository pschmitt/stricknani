<script lang="ts">
  import { onDestroy, onMount, type Snippet } from "svelte";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";

  export interface ActiveFilterChip {
    key: string;
    label: string;
    remove: () => void;
  }

  interface Props {
    query: string;
    queryLabel: string;
    placeholder: string;
    filterLabel: string;
    closeLabel: string;
    clearLabel: string;
    removeFilterLabel: string;
    activeFilterCount: number;
    chips: ActiveFilterChip[];
    onquerydraft: (query: string) => void;
    onquerycommit: (query: string) => void;
    onclear: () => void;
    filterControls: Snippet;
  }

  let {
    query,
    queryLabel,
    placeholder,
    filterLabel,
    closeLabel,
    clearLabel,
    removeFilterLabel,
    activeFilterCount,
    chips,
    onquerydraft,
    onquerycommit,
    onclear,
    filterControls,
  }: Props = $props();

  const idPrefix = $props.id();
  const dialogId = `${idPrefix}-filters`;
  const dialogTitleId = `${idPrefix}-filters-title`;
  let queryDraft = $state("");
  let lastSyncedQuery = "";
  let draftRevision = 0;
  let forceUrlResync = $state(false);
  const pendingQueryCommits: { query: string; revision: number }[] = [];
  let dialog = $state<HTMLDialogElement>();
  let filterButtonHost = $state<HTMLDivElement>();
  let filtersOpen = $state(false);
  let openedAsModal = false;
  let debounceTimer: ReturnType<typeof setTimeout> | undefined;

  $effect(() => {
    const urlQuery = query;
    const isUrlResync = forceUrlResync;
    if (urlQuery !== lastSyncedQuery || isUrlResync) {
      forceUrlResync = false;
      lastSyncedQuery = urlQuery;
      if (isUrlResync) {
        pendingQueryCommits.length = 0;
        draftRevision += 1;
        cancelDebounce();
        queryDraft = urlQuery;
        onquerydraft(urlQuery);
        return;
      }
      const acknowledgedIndex = pendingQueryCommits.findIndex(
        (commit) => commit.query === urlQuery,
      );
      if (acknowledgedIndex !== -1) {
        const acknowledged = pendingQueryCommits[acknowledgedIndex];
        pendingQueryCommits.splice(0, acknowledgedIndex + 1);
        if (acknowledged?.revision === draftRevision) queryDraft = urlQuery;
        return;
      }
      pendingQueryCommits.length = 0;
      draftRevision += 1;
      cancelDebounce();
      queryDraft = urlQuery;
      onquerydraft(urlQuery);
    }
  });

  function onPopState() {
    forceUrlResync = true;
  }

  onMount(() => {
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  });

  onDestroy(() => {
    cancelDebounce();
    closeFilters(false);
  });

  function cancelDebounce() {
    if (debounceTimer !== undefined) clearTimeout(debounceTimer);
    debounceTimer = undefined;
  }

  function commitQuery(value: string, revision = draftRevision) {
    cancelDebounce();
    const normalized = value.trim();
    const latestPending = pendingQueryCommits.at(-1);
    if (
      normalized !== query ||
      (latestPending !== undefined && latestPending.query !== normalized)
    ) {
      pendingQueryCommits.push({ query: normalized, revision });
      onquerycommit(normalized);
    }
  }

  function scheduleQuery(value: string) {
    cancelDebounce();
    const revision = draftRevision;
    debounceTimer = setTimeout(() => commitQuery(value, revision), 200);
  }

  function positionDesktopDialog() {
    if (!dialog?.open) return;
    const trigger = filterButtonHost
      ?.querySelector("button")
      ?.getBoundingClientRect();
    if (!trigger) return;

    const panel = dialog.getBoundingClientRect();
    const left = Math.max(
      16,
      Math.min(trigger.left, window.innerWidth - panel.width - 16),
    );
    const maxTop = Math.max(16, window.innerHeight - panel.height - 16);
    const top = Math.max(16, Math.min(trigger.bottom + 8, maxTop));
    dialog.style.left = `${left}px`;
    dialog.style.top = `${top}px`;
  }

  function openFilters() {
    if (!dialog) return;
    if (dialog.open) {
      closeFilters();
      return;
    }
    if (window.matchMedia("(max-width: 47.999rem)").matches) {
      openedAsModal = true;
      dialog.style.removeProperty("left");
      dialog.style.removeProperty("top");
      dialog.showModal();
      filtersOpen = true;
      window.addEventListener("resize", onViewportResize);
      return;
    }

    openedAsModal = false;
    dialog.show();
    positionDesktopDialog();
    filtersOpen = true;
    document.addEventListener("pointerdown", onOutsidePointerDown, true);
    document.addEventListener("keydown", onDesktopKeydown, true);
    window.addEventListener("resize", onViewportResize);
  }

  function closeFilters(restoreFocus = true) {
    if (typeof document !== "undefined") {
      document.removeEventListener("pointerdown", onOutsidePointerDown, true);
      document.removeEventListener("keydown", onDesktopKeydown, true);
    }
    if (typeof window !== "undefined") {
      window.removeEventListener("resize", onViewportResize);
    }
    if (dialog?.open) dialog.close();
    else filtersOpen = false;
    if (restoreFocus) {
      filterButtonHost?.querySelector<HTMLButtonElement>("button")?.focus();
    }
  }

  function onOutsidePointerDown(event: PointerEvent) {
    if (
      dialog?.open &&
      !openedAsModal &&
      !dialog.contains(event.target as Node) &&
      !filterButtonHost?.contains(event.target as Node)
    ) {
      closeFilters(false);
    }
  }

  function onDesktopKeydown(event: KeyboardEvent) {
    if (event.key === "Escape" && dialog?.open && !openedAsModal) {
      event.preventDefault();
      closeFilters();
    }
  }

  function onViewportResize() {
    if (!dialog?.open) return;
    const isMobile = window.matchMedia("(max-width: 47.999rem)").matches;
    if (openedAsModal !== isMobile) {
      closeFilters(false);
      dialog.style.removeProperty("left");
      dialog.style.removeProperty("top");
    } else if (!isMobile) {
      positionDesktopDialog();
    }
  }

  function restoreFilterFocus() {
    closeFilters(false);
  }

  function clearFilters() {
    cancelDebounce();
    draftRevision += 1;
    queryDraft = "";
    onclear();
    closeFilters();
  }
</script>

<div class="library-controls">
  <form
    class="search-row"
    role="search"
    aria-label={queryLabel}
    onsubmit={(event) => {
      event.preventDefault();
      commitQuery(queryDraft);
    }}
  >
    <label class="search-field">
      <span class="visually-hidden">{queryLabel}</span>
      <NavIcon name="search" />
      <input
        type="search"
        aria-label={queryLabel}
        {placeholder}
        autocomplete="off"
        bind:value={queryDraft}
        oninput={(event) => {
          draftRevision += 1;
          onquerydraft(event.currentTarget.value);
          scheduleQuery(event.currentTarget.value);
        }}
      />
      {#if queryDraft}
        <Button
          class="clear-query"
          icon="close"
          aria-label={`${removeFilterLabel} ${queryLabel}`}
          onclick={() => {
            draftRevision += 1;
            queryDraft = "";
            onquerydraft("");
            commitQuery("");
          }}
        />
      {/if}
    </label>
    <div bind:this={filterButtonHost} class="filter-trigger-host">
      <Button
        class="filter-trigger"
        icon="filter"
        aria-label={activeFilterCount
          ? `${filterLabel} (${activeFilterCount})`
          : filterLabel}
        aria-haspopup="dialog"
        aria-expanded={filtersOpen}
        aria-controls={dialogId}
        onclick={openFilters}
      >
        <span class="filter-trigger__label">{filterLabel}</span>
      </Button>
      {#if activeFilterCount}
        <span class="filter-count" aria-hidden="true">{activeFilterCount}</span>
      {/if}
    </div>
  </form>
</div>

{#if chips.length}
  <div class="active-filters" role="group" aria-label={filterLabel}>
    {#each chips as chip (chip.key)}
      <span class="filter-chip">
        <span>{chip.label}</span>
        <Button
          class="chip-remove"
          size="sm"
          icon="close"
          aria-label={`${removeFilterLabel} ${chip.label}`}
          onclick={chip.remove}
        />
      </span>
    {/each}
  </div>
{/if}

<dialog
  bind:this={dialog}
  id={dialogId}
  class="filter-panel"
  aria-labelledby={dialogTitleId}
  onclose={restoreFilterFocus}
  onclick={(event) => {
    if (event.target === event.currentTarget) {
      const bounds = dialog?.getBoundingClientRect();
      if (
        bounds &&
        (event.clientX < bounds.left ||
          event.clientX > bounds.right ||
          event.clientY < bounds.top ||
          event.clientY > bounds.bottom)
      ) {
        closeFilters();
      }
    }
  }}
>
  <div class="filter-panel__header">
    <h2 id={dialogTitleId}>{filterLabel}</h2>
    <Button
      icon="close"
      aria-label={closeLabel}
      onclick={() => closeFilters()}
    />
  </div>
  <div class="filter-panel__body">
    {@render filterControls()}
  </div>
  {#if activeFilterCount}
    <div class="filter-panel__footer">
      <Button variant="ghost" icon="reset" onclick={clearFilters}>
        {clearLabel}
      </Button>
    </div>
  {/if}
</dialog>

<style>
  .library-controls {
    position: sticky;
    z-index: 12;
    top: var(--app-header-height, 4.25rem);
    margin-inline: -0.35rem;
    padding: 0.4rem 0.35rem;
    background: var(--canvas);
  }

  .search-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 0.5rem;
    margin: 0;
  }

  .search-field {
    display: flex;
    min-width: 0;
    min-height: 48px;
    align-items: center;
    gap: 0.65rem;
    border: 1px solid var(--control-border);
    border-radius: 0.75rem;
    padding: 0 0.55rem 0 0.8rem;
    color: var(--ink-muted);
    background: var(--surface);
  }

  .search-field :global(svg) {
    width: 1.15rem;
    height: 1.15rem;
    flex: none;
  }

  .search-field input {
    width: 100%;
    min-width: 0;
    min-height: 46px;
    border: 0;
    outline: 0;
    color: var(--ink);
    background: transparent;
    font: inherit;
  }

  .search-field input::-webkit-search-cancel-button {
    display: none;
  }

  .search-field:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .search-field input:focus-visible {
    outline: 0;
  }

  :global(.clear-query.ui-button) {
    width: 44px;
    min-width: 44px;
    height: 44px;
    min-height: 44px;
    padding: 0;
  }

  .filter-trigger-host {
    position: relative;
    justify-self: end;
  }

  :global(.filter-trigger.ui-button) {
    min-width: 44px;
    gap: 0.35rem;
    white-space: nowrap;
  }

  .filter-count {
    display: inline-grid;
    min-width: 1.35rem;
    height: 1.35rem;
    place-items: center;
    border-radius: 999px;
    padding-inline: 0.25rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 0.75rem;
    font-weight: 700;
  }

  .active-filters {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
    margin-top: 0.25rem;
  }

  .filter-chip {
    display: inline-flex;
    min-height: 44px;
    max-width: 100%;
    align-items: center;
    gap: 0.35rem;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.15rem 0.2rem 0.15rem 0.7rem;
    color: var(--ink);
    background: var(--surface);
    font-size: 0.82rem;
  }

  .filter-chip > span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :global(.chip-remove.ui-button) {
    width: 44px;
    min-width: 44px;
    height: 44px;
    min-height: 44px;
    padding: 0;
  }

  .filter-panel {
    box-sizing: border-box;
    position: fixed;
    z-index: 30;
    inset: auto;
    width: min(28rem, calc(100vw - 2rem));
    max-width: none;
    max-height: min(75dvh, calc(100dvh - 2rem), 38rem);
    margin: 0;
    overflow: auto;
    overscroll-behavior: contain;
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: 1rem;
    color: var(--ink);
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  .filter-panel::backdrop {
    background: rgb(15 27 40 / 42%);
  }

  .filter-panel__header,
  .filter-panel__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
  }

  .filter-panel__header h2 {
    margin: 0;
    font-size: 1.1rem;
  }

  .filter-panel__body {
    display: grid;
    gap: 1rem;
    padding-block: 1rem;
  }

  .filter-panel__footer {
    justify-content: flex-end;
    border-top: 1px solid var(--line);
    padding-top: 0.75rem;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    clip-path: inset(50%);
  }

  @media (max-width: 47.999rem) {
    :global(.filter-trigger.ui-button) {
      width: 44px;
      min-width: 44px;
      padding-inline: 0;
    }

    .filter-trigger__label {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      clip-path: inset(50%);
    }

    .filter-count {
      position: absolute;
      z-index: 1;
      top: -0.25rem;
      right: -0.25rem;
    }

    .filter-panel {
      inset: auto 0 0;
      width: 100%;
      max-height: min(82dvh, 42rem);
      border: 0;
      border-radius: 1.1rem 1.1rem 0 0;
      padding: 1rem max(1rem, env(safe-area-inset-right))
        calc(1rem + env(safe-area-inset-bottom))
        max(1rem, env(safe-area-inset-left));
    }
  }
</style>
