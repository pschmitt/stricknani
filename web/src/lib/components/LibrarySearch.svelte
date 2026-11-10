<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";

  interface Props {
    query: string;
    queryLabel: string;
    placeholder: string;
    removeFilterLabel: string;
    onquerydraft: (query: string) => void;
    onquerycommit: (query: string) => void;
  }

  let {
    query,
    queryLabel,
    placeholder,
    removeFilterLabel,
    onquerydraft,
    onquerycommit,
  }: Props = $props();

  let queryDraft = $state("");
  let lastSyncedQuery = "";
  let draftRevision = 0;
  let forceUrlResync = $state(false);
  const pendingQueryCommits: { query: string; revision: number }[] = [];
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
        if (acknowledged?.revision === draftRevision) {
          queryDraft = urlQuery;
          onquerydraft(urlQuery);
        }
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

  onDestroy(cancelDebounce);

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

  function clearQuery() {
    draftRevision += 1;
    queryDraft = "";
    onquerydraft("");
    commitQuery("");
  }
</script>

<form
  class="library-search"
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
        onclick={clearQuery}
      />
    {/if}
  </label>
</form>

<style>
  .library-search {
    margin: 0;
  }

  .search-field {
    display: flex;
    min-width: 0;
    min-height: 48px;
    align-items: center;
    gap: 0.65rem;
    border: 1px solid var(--control-border);
    border-radius: var(--radius-xs);
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
    border-radius: 0;
    padding: 0;
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
</style>
