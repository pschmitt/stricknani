<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { visiblePeople } from "./people-filter.ts";
  import {
    SEARCH_QUERY_LIMIT,
    searchPeople,
    type Person,
  } from "./sharing-api.ts";

  let {
    locale,
    inputId,
    excludeIds = [],
    selected = null,
    onselect,
  }: {
    locale: Locale;
    /** Unique id of the search field, so its label association is explicit. */
    inputId: string;
    /** People who already have access are not offered again. */
    excludeIds?: number[];
    selected?: Person | null;
    onselect: (person: Person | null) => void;
  } = $props();

  let query = $state("");
  let results = $state<Person[]>([]);
  let page = $state(1);
  let hasMore = $state(false);
  let loading = $state(false);
  let error = $state("");
  let generation = 0;
  let controller: AbortController | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;

  const visible = $derived(visiblePeople(results, excludeIds, query));

  async function load(reset: boolean) {
    const current = ++generation;
    controller?.abort();
    controller = new AbortController();
    const signal = controller.signal;
    const nextPage = reset ? 1 : page + 1;
    loading = true;
    error = "";
    try {
      const result = await searchPeople(query, nextPage, signal);
      if (signal.aborted || current !== generation) return;
      results = reset ? result.items : [...results, ...result.items];
      page = result.page;
      hasMore = result.has_more;
    } catch (failure) {
      if (signal.aborted || current !== generation) return;
      error =
        failure instanceof ApiError
          ? failure.message
          : t("People could not be loaded.", locale);
    } finally {
      if (current === generation) loading = false;
    }
  }

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(() => void load(true), 250);
  }

  onMount(() => {
    void load(true);
  });

  onDestroy(() => {
    clearTimeout(timer);
    controller?.abort();
  });
</script>

{#if selected}
  <div class="people-picker">
    <span class="people-picker__label">{t("Selected person", locale)}</span>
    <div class="people-picker__selected">
      <UserAvatar avatarUrl={selected.avatar_url} name={selected.name} />
      <span class="people-picker__name">{selected.name}</span>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        onclick={() => onselect(null)}
      >
        {t("Change", locale)}
      </Button>
    </div>
  </div>
{:else}
  <div class="people-picker">
    <label for={inputId}>{t("Find people", locale)}</label>
    <input
      id={inputId}
      type="search"
      value={query}
      maxlength={SEARCH_QUERY_LIMIT}
      autocomplete="off"
      placeholder={t("Search by name", locale)}
      oninput={(event) => {
        query = event.currentTarget.value;
        schedule();
      }}
    />
    {#if error}
      <p class="people-picker__error" role="alert">
        {error}
        <button
          type="button"
          class="people-picker__retry"
          onclick={() => void load(true)}>{t("Try again", locale)}</button
        >
      </p>
    {/if}
    {#if visible.length}
      <ul class="people-picker__list" aria-label={t("People", locale)}>
        {#each visible as person (person.id)}
          <li>
            <button
              type="button"
              class="people-picker__person"
              onclick={() => onselect(person)}
            >
              <UserAvatar avatarUrl={person.avatar_url} name={person.name} />
              <span class="people-picker__name">{person.name}</span>
            </button>
          </li>
        {/each}
      </ul>
      {#if hasMore}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={loading}
          onclick={() => void load(false)}
        >
          {t("Show more", locale)}
        </Button>
      {/if}
    {:else if !loading && !error}
      <p class="people-picker__empty" role="status">
        {query.trim()
          ? t("No one matches that search.", locale)
          : t("There is no one else to share with yet.", locale)}
      </p>
    {/if}
    {#if loading && !visible.length}
      <p class="people-picker__empty" role="status">{t("Loading…", locale)}</p>
    {/if}
  </div>
{/if}

<style>
  .people-picker {
    display: grid;
    min-width: 0;
    gap: 0.5rem;
  }

  .people-picker label,
  .people-picker__label {
    font-weight: var(--ui-label-font-weight);
  }

  .people-picker__selected {
    display: flex;
    min-height: 3rem;
    align-items: center;
    gap: 0.7rem;
    border: 1px solid var(--line);
    border-left: 3px solid var(--accent-strong);
    border-radius: var(--radius-xs);
    padding: 0.35rem 0.5rem 0.35rem 0.75rem;
    background: var(--surface-raised);
  }

  .people-picker input {
    width: 100%;
  }

  .people-picker__list {
    display: grid;
    max-height: 13rem;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    list-style: none;
  }

  .people-picker__list li + li {
    border-top: 1px solid var(--line);
  }

  .people-picker__person {
    display: flex;
    width: 100%;
    min-height: 3rem;
    align-items: center;
    gap: 0.7rem;
    border: 0;
    padding: 0.35rem 0.75rem;
    color: var(--ink);
    background: transparent;
    cursor: pointer;
    text-align: left;
  }

  .people-picker__person:hover {
    background: var(--surface-raised);
  }

  .people-picker__person:focus-visible {
    outline: 2px solid var(--accent-strong);
    outline-offset: -2px;
  }

  .people-picker__name {
    min-width: 0;
    flex: 1;
    overflow-wrap: anywhere;
  }

  .people-picker__empty,
  .people-picker__error {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.92rem;
  }

  .people-picker__error {
    color: var(--danger);
  }

  .people-picker__retry {
    border: 0;
    padding: 0;
    color: inherit;
    background: none;
    font-weight: 600;
    text-decoration: underline;
    cursor: pointer;
  }
</style>
