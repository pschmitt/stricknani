<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import UserLabel from "#lib/components/UserLabel.svelte";
  import { session } from "#lib/session.svelte.ts";
  import { nameUser } from "#lib/user-label.ts";
  import {
    SUGGESTION_GROUPS,
    type FilterSuggestion,
    type SuggestionGroup,
  } from "#lib/search-suggestions.ts";

  /**
   * Typeahead filter suggestions below the field (an ARIA combobox with a listbox): the host
   * loads the matches for the typed text, [select] applies the picked one. Without it the
   * field is a plain search box.
   */
  export interface SuggestionConfig {
    load: (query: string, signal: AbortSignal) => Promise<FilterSuggestion[]>;
    select: (suggestion: FilterSuggestion) => void;
    groupLabels: Record<SuggestionGroup, string>;
    /** The accessible name of the list. */
    listLabel: string;
    /** What a screen reader hears when the list opens. */
    announce: (count: number) => string;
  }

  interface Props {
    query: string;
    queryLabel: string;
    placeholder: string;
    removeFilterLabel: string;
    onquerydraft: (query: string) => void;
    onquerycommit: (query: string) => void;
    suggestions?: SuggestionConfig;
  }

  let {
    query,
    queryLabel,
    placeholder,
    removeFilterLabel,
    onquerydraft,
    onquerycommit,
    suggestions,
  }: Props = $props();

  const locale = $derived(session.data?.locale ?? "en");
  const idPrefix = $props.id();
  const listId = `${idPrefix}-suggestions`;
  const optionId = (index: number) => `${idPrefix}-suggestion-${index}`;
  const headingId = (group: SuggestionGroup) => `${idPrefix}-group-${group}`;
  let options = $state<FilterSuggestion[]>([]);
  let listOpen = $state(false);
  let activeIndex = $state(-1);
  let announcement = $state("");
  let suggestTimer: ReturnType<typeof setTimeout> | undefined;
  let suggestAbort: AbortController | undefined;
  let suggestGeneration = 0;
  const groups = $derived(
    SUGGESTION_GROUPS.map((group) => ({
      group,
      items: options.flatMap((suggestion, index) =>
        suggestion.group === group ? [{ suggestion, index }] : [],
      ),
    })).filter((entry) => entry.items.length > 0),
  );

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

  onDestroy(() => {
    cancelDebounce();
    cancelSuggestions();
  });

  function cancelSuggestions() {
    suggestGeneration += 1;
    if (suggestTimer !== undefined) clearTimeout(suggestTimer);
    suggestTimer = undefined;
    suggestAbort?.abort();
    suggestAbort = undefined;
  }

  function closeSuggestions() {
    cancelSuggestions();
    options = [];
    listOpen = false;
    activeIndex = -1;
    announcement = "";
  }

  function scheduleSuggestions(value: string) {
    if (!suggestions) return;
    cancelSuggestions();
    if (!value.trim()) {
      closeSuggestions();
      return;
    }
    const generation = suggestGeneration;
    suggestTimer = setTimeout(() => {
      const config = suggestions;
      if (!config) return;
      const controller = new AbortController();
      suggestAbort = controller;
      void config
        .load(value, controller.signal)
        .then((loaded) => {
          if (generation !== suggestGeneration) return;
          options = loaded;
          activeIndex = -1;
          listOpen = loaded.length > 0;
          announcement = loaded.length ? config.announce(loaded.length) : "";
        })
        .catch(() => {
          if (generation === suggestGeneration) closeSuggestions();
        });
    }, 120);
  }

  function selectSuggestion(suggestion: FilterSuggestion) {
    const config = suggestions;
    closeSuggestions();
    cancelDebounce();
    // The typed text was only the way to find the filter: drop it and the text search with it.
    draftRevision += 1;
    queryDraft = "";
    onquerydraft("");
    config?.select(suggestion);
  }

  function onInputKeydown(event: KeyboardEvent) {
    if (!suggestions) return;
    const count = options.length;
    if (event.key === "ArrowDown" && count > 0) {
      event.preventDefault();
      if (!listOpen) {
        listOpen = true;
        activeIndex = 0;
      } else {
        activeIndex = (activeIndex + 1) % count;
      }
    } else if (event.key === "ArrowUp" && listOpen) {
      event.preventDefault();
      activeIndex = activeIndex <= 0 ? count - 1 : activeIndex - 1;
    } else if (event.key === "Enter" && listOpen && activeIndex >= 0) {
      event.preventDefault();
      const picked = options[activeIndex];
      if (picked) selectSuggestion(picked);
    } else if (event.key === "Escape" && listOpen) {
      event.preventDefault();
      event.stopPropagation();
      listOpen = false;
      activeIndex = -1;
    }
  }

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
    closeSuggestions();
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
    closeSuggestions();
    commitQuery(queryDraft);
  }}
>
  <label class="search-field">
    <NavIcon name="search" />
    <input
      type="search"
      role={suggestions ? "combobox" : undefined}
      aria-label={queryLabel}
      aria-autocomplete={suggestions ? "list" : undefined}
      aria-expanded={suggestions ? listOpen : undefined}
      aria-controls={suggestions && listOpen ? listId : undefined}
      aria-activedescendant={suggestions && listOpen && activeIndex >= 0
        ? optionId(activeIndex)
        : undefined}
      {placeholder}
      autocomplete="off"
      bind:value={queryDraft}
      oninput={(event) => {
        draftRevision += 1;
        onquerydraft(event.currentTarget.value);
        scheduleQuery(event.currentTarget.value);
        scheduleSuggestions(event.currentTarget.value);
      }}
      onkeydown={onInputKeydown}
      onblur={closeSuggestions}
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
  {#if suggestions}
    <div class="visually-hidden" role="status" aria-live="polite">
      {announcement}
    </div>
    {#if listOpen}
      <div
        id={listId}
        class="suggestion-list"
        role="listbox"
        aria-label={suggestions.listLabel}
        data-testid="filter-suggestions"
      >
        {#each groups as entry (entry.group)}
          <div
            class="suggestion-group"
            role="group"
            aria-labelledby={headingId(entry.group)}
          >
            <div
              id={headingId(entry.group)}
              class="suggestion-heading"
              role="presentation"
            >
              {suggestions.groupLabels[entry.group]}
            </div>
            {#each entry.items as { suggestion, index } (suggestion.id)}
              <!-- The input keeps the focus; the keyboard acts through aria-activedescendant. -->
              <!-- svelte-ignore a11y_click_events_have_key_events -->
              <div
                id={optionId(index)}
                class="suggestion-option"
                class:suggestion-option--active={index === activeIndex}
                role="option"
                tabindex="-1"
                aria-selected={index === activeIndex}
                data-testid="filter-suggestion"
                onmousedown={(event) => event.preventDefault()}
                onclick={() => selectSuggestion(suggestion)}
              >
                {#if suggestion.group === "owners" && suggestion.avatarUrl !== undefined}
                  <UserLabel
                    user={nameUser(suggestion.label, suggestion.avatarUrl)}
                    {locale}
                  />
                {:else}
                  <span>{suggestion.label}</span>
                {/if}
              </div>
            {/each}
          </div>
        {/each}
      </div>
    {/if}
  {/if}
</form>

<style>
  .library-search {
    position: relative;
    width: 100%;
    margin: 0;
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

  .suggestion-list {
    position: absolute;
    z-index: 20;
    top: calc(100% + 0.25rem);
    right: 0;
    left: 0;
    max-height: min(22rem, 55dvh);
    overflow-y: auto;
    overscroll-behavior: contain;
    border: 1px solid var(--line);
    border-radius: var(--radius-lg);
    padding: 0.5rem 0;
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  .suggestion-heading {
    padding: 0.5rem 1.25rem 0.2rem;
    color: var(--ink-muted);
    font-size: 0.75rem;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }

  .suggestion-option {
    display: flex;
    min-height: 44px;
    align-items: center;
    gap: 0.6rem;
    padding: 0.35rem 1.25rem;
    color: var(--ink);
    cursor: pointer;
  }

  .suggestion-option > span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .suggestion-option:hover,
  .suggestion-option--active {
    background: var(--accent-wash);
  }

  .suggestion-option--active {
    outline: 2px solid var(--focus);
    outline-offset: -2px;
  }

  .search-field {
    display: flex;
    width: 100%;
    min-width: 0;
    min-height: 56px;
    align-items: center;
    gap: 0.75rem;
    border: 0;
    border-radius: var(--radius-lg);
    padding: 0 0.5rem 0 1rem;
    color: var(--ink-muted);
    background: var(--surface-highest);
  }

  .search-field :global(svg) {
    width: 1.5rem;
    height: 1.5rem;
    flex: none;
  }

  .search-field input {
    width: 100%;
    min-width: 0;
    min-height: 54px;
    border: 0;
    border-radius: 0;
    padding: 0;
    outline: 0;
    color: var(--ink);
    background: transparent;
    font: inherit;
  }

  .search-field input::placeholder {
    color: var(--ink-muted);
    opacity: 1;
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
