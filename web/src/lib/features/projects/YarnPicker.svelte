<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import type { YarnSummary } from "../yarns/yarn-api";

  type SelectedYarn = { id: number; name: string };

  let {
    locale,
    query,
    results,
    selected,
    loading,
    error,
    onQueryChange,
    onSearch,
    onAdd,
    onRemove,
  }: {
    locale: Locale;
    query: string;
    results: YarnSummary[];
    selected: SelectedYarn[];
    loading: boolean;
    error: string;
    onQueryChange: (value: string) => void;
    onSearch: () => void;
    onAdd: (yarn: YarnSummary) => void;
    onRemove: (id: number) => void;
  } = $props();

  const selectedIds = $derived(new Set(selected.map((yarn) => yarn.id)));
  const availableResults = $derived(
    results.filter((yarn) => !selectedIds.has(yarn.id)),
  );
</script>

<div class="yarn-picker">
  <div class="yarn-search" role="search" aria-label={t("Search yarn", locale)}>
    <label for="project-yarn-search">{t("Find yarn", locale)}</label>
    <div class="search-row">
      <input
        id="project-yarn-search"
        value={query}
        autocomplete="off"
        oninput={(event) => onQueryChange(event.currentTarget.value)}
        onkeydown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            onSearch();
          }
        }}
      />
      <Button
        type="button"
        class="secondary-button"
        variant="secondary"
        icon="search"
        onclick={onSearch}
      >
        {t("Search", locale)}
      </Button>
    </div>
  </div>
  {#if error}<p class="field-error" role="alert">{error}</p>{/if}
  {#if loading}
    <p class="field-hint" aria-live="polite">
      {t("Loading yarn options…", locale)}
    </p>
  {/if}
  {#if selected.length || availableResults.length}
    <ul>
      {#each selected as yarn (yarn.id)}
        <li>
          <span>{yarn.name}</span>
          <Button
            type="button"
            class="remove-button"
            variant="ghost"
            icon="action-delete"
            aria-label={`${t("Remove", locale)} ${yarn.name}`}
            onclick={() => onRemove(yarn.id)}
          >
            {t("Remove", locale)}
          </Button>
        </li>
      {/each}
      {#each availableResults as yarn (yarn.id)}
        <li>
          <span>
            <strong>{yarn.name}</strong>
            {#if yarn.brand}<small>{yarn.brand}</small>{/if}
            {#if yarn.colorway}<small>{yarn.colorway}</small>{/if}
          </span>
          <Button
            type="button"
            class="secondary-button"
            variant="secondary"
            icon="create"
            onclick={() => onAdd(yarn)}
          >
            {t("Add yarn", locale)}
          </Button>
        </li>
      {/each}
    </ul>
  {:else if !loading && query.trim()}
    <p class="field-hint">{t("No yarn matches that search.", locale)}</p>
  {:else if !selected.length}
    <p class="field-hint">
      {t("No yarn is linked to this project yet.", locale)}
    </p>
  {/if}
</div>

<style>
  .yarn-picker {
    display: grid;
    min-width: 0;
    gap: 0.7rem;
  }

  .yarn-search {
    display: grid;
    gap: 0.4rem;
  }

  .search-row {
    display: grid;
    min-width: 0;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 0.5rem;
  }

  input {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    min-height: 44px;
    border: 1px solid var(--control-border);
    border-radius: var(--radius-xs);
    padding: 0.65rem 0.75rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
  }

  ul {
    display: grid;
    min-width: 0;
    gap: 0.5rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  li {
    display: flex;
    min-width: 0;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 0.5rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.6rem;
    background: var(--surface-raised);
  }

  li > span {
    display: grid;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  small {
    color: var(--ink-muted);
  }

  .field-hint,
  .field-error {
    margin: 0;
    font-size: 0.86rem;
  }

  .field-hint {
    color: var(--ink-muted);
  }

  .field-error {
    color: var(--danger);
  }
</style>
