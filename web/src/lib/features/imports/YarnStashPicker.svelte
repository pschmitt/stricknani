<script lang="ts">
  import { onDestroy } from "svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { listYarns, type YarnSummary } from "../yarns/yarn-api";

  // A searchable list of the user's own yarns. Each result is a compact resource card; picking
  // one reports it to the parent. Used for "use a yarn from my stash" and "add another yarn".
  let {
    locale,
    inputId,
    label,
    exclude = [],
    disabled = false,
    onPick,
  }: {
    locale: Locale;
    inputId: string;
    label: string;
    exclude?: number[];
    disabled?: boolean;
    onPick: (yarn: YarnSummary) => void;
  } = $props();

  const RESULTS_SHOWN = 6;
  const DEBOUNCE_MS = 250;

  let query = $state("");
  let results = $state<YarnSummary[]>([]);
  let loading = $state(false);
  let failed = $state(false);
  let loaded = $state(false);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | null = null;

  const visible = $derived(
    results
      .filter((yarn) => !exclude.includes(yarn.id))
      .slice(0, RESULTS_SHOWN),
  );

  async function search(value: string) {
    controller?.abort();
    const active = new AbortController();
    controller = active;
    loading = true;
    failed = false;
    try {
      const params = new URLSearchParams();
      if (value.trim()) params.set("q", value.trim());
      const page = await listYarns(params.toString(), active.signal);
      if (controller !== active) return;
      results = page.data;
      loaded = true;
    } catch (error) {
      if (controller !== active) return;
      if (error instanceof DOMException && error.name === "AbortError") return;
      failed = true;
    } finally {
      if (controller === active) {
        loading = false;
        controller = null;
      }
    }
  }

  function changed(value: string) {
    query = value;
    clearTimeout(timer);
    timer = setTimeout(() => void search(value), DEBOUNCE_MS);
  }

  function opened() {
    if (!loaded && !loading) void search(query);
  }

  onDestroy(() => {
    clearTimeout(timer);
    controller?.abort();
  });

  function detail(yarn: YarnSummary): string {
    return [yarn.brand, yarn.colorway, yarn.weight_category]
      .filter(Boolean)
      .join(" · ");
  }
</script>

<div class="stash-picker">
  <label for={inputId}>{label}</label>
  <input
    id={inputId}
    type="search"
    autocomplete="off"
    placeholder={t("Search your yarns", locale)}
    value={query}
    {disabled}
    onfocus={opened}
    oninput={(event) => changed(event.currentTarget.value)}
  />
  {#if failed}<p class="stash-picker__note" role="alert">
      {t("Your yarns could not be loaded.", locale)}
    </p>{/if}
  {#if loading}<p class="stash-picker__note" aria-live="polite">
      {t("Loading yarn options…", locale)}
    </p>{:else if loaded && !visible.length && !failed}<p
      class="stash-picker__note"
    >
      {t("No yarn matches that search.", locale)}
    </p>{/if}
  {#if visible.length}
    <ul>
      {#each visible as yarn (yarn.id)}
        <li>
          <button
            type="button"
            class="stash-picker__option"
            {disabled}
            onclick={() => onPick(yarn)}
          >
            <ResourceCard
              kind="yarn"
              href={null}
              imageUrl={yarn.preview_url}
              name={yarn.name}
              subtitle={detail(yarn)}
            />
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .stash-picker {
    display: grid;
    min-width: 0;
    gap: 0.4rem;
  }

  input {
    width: 100%;
    min-width: 0;
  }

  ul {
    display: grid;
    gap: 0.4rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .stash-picker__option {
    display: block;
    width: 100%;
    min-height: 44px;
    padding: 0.3rem 0.45rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
    color: inherit;
    text-align: start;
    cursor: pointer;
  }

  .stash-picker__option:hover:not(:disabled) {
    background: var(--accent-wash);
  }

  .stash-picker__option:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }

  .stash-picker__note {
    margin: 0;
    font-size: 0.9rem;
    opacity: 0.8;
  }
</style>
