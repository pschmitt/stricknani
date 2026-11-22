<script lang="ts">
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import {
    dryingOptions,
    washTemperatures,
    type Drying,
    type WashingSymbols,
  } from "./washing-symbols";
  import SectionHeading from "./SectionHeading.svelte";

  let {
    locale,
    value,
    symbols,
    onSymbols,
    onInput,
    disabled = false,
    textSizeClass = "",
  }: {
    locale: Locale;
    value: string | null | undefined;
    symbols?: WashingSymbols | null;
    onSymbols?: (value: WashingSymbols) => void;
    onInput?: (value: string) => void;
    disabled?: boolean;
    textSizeClass?: string;
  } = $props();

  const headingId = $props.id();
  function dryingLabel(option: Drying): string {
    switch (option) {
      case "flat":
        return t("Dry flat", locale);
      case "line":
        return t("Line dry", locale);
      case "no_tumble":
        return t("Do not tumble dry", locale);
    }
  }
</script>

{#snippet symbol(option: Drying | "wash")}
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    {#if option === "wash"}
      <path d="m3 6 3 13h12l3-13M4.1 10c3.4-4 5.2 4 8.4 0 3.1-4 4.5 4 7.4 0" />
    {:else}
      <rect x="4" y="4" width="16" height="16" rx="1.5" />
      {#if option === "flat"}
        <path d="M8 12h8" />
      {:else if option === "line"}
        <path d="M12 8v8" />
      {:else}
        <circle cx="12" cy="12" r="5" />
        <path d="m5 5 14 14m0-14L5 19" />
      {/if}
    {/if}
  </svg>
{/snippet}

{#if onInput || value || symbols?.temperature || symbols?.drying}
  <section class="washing-card" aria-labelledby={headingId}>
    <SectionHeading icon="laundry" tone="yarns" id={headingId}
      >{t("Washing instructions", locale)}</SectionHeading
    >
    {#if onSymbols}
      <div
        class="symbol-group"
        role="group"
        aria-label={t("Washing temperature", locale)}
      >
        <span class="symbol-label">{t("Washing temperature", locale)}</span>
        <div class="symbol-options">
          {#each washTemperatures as option (option)}
            <button
              type="button"
              {disabled}
              aria-pressed={symbols?.temperature === option}
              onclick={() =>
                onSymbols?.({
                  ...symbols,
                  temperature: symbols?.temperature === option ? null : option,
                })}
            >
              {@render symbol("wash")}{option} °C
            </button>
          {/each}
        </div>
      </div>
      <div class="symbol-group" role="group" aria-label={t("Drying", locale)}>
        <span class="symbol-label">{t("Drying", locale)}</span>
        <div class="symbol-options">
          {#each dryingOptions as option (option)}
            <button
              type="button"
              {disabled}
              aria-pressed={symbols?.drying === option}
              onclick={() =>
                onSymbols?.({
                  ...symbols,
                  drying: symbols?.drying === option ? null : option,
                })}
            >
              {@render symbol(option)}{dryingLabel(option)}
            </button>
          {/each}
        </div>
      </div>
    {:else if symbols?.temperature || symbols?.drying}
      <ul class="symbol-options read-symbols">
        {#if symbols.temperature}<li>
            {@render symbol("wash")}{symbols.temperature} °C
          </li>{/if}
        {#if symbols.drying}<li>
            {@render symbol(symbols.drying)}{dryingLabel(symbols.drying)}
          </li>{/if}
      </ul>
    {/if}
    {#if onInput}
      <textarea
        aria-labelledby={headingId}
        rows={3}
        {disabled}
        value={value ?? ""}
        oninput={(event) => onInput?.(event.currentTarget.value)}></textarea>
    {:else if value}
      <p class={textSizeClass}>{value}</p>
    {/if}
  </section>
{/if}

<style>
  .washing-card {
    display: grid;
    gap: 0.75rem;
    min-width: 0;
    margin-block-start: 0.75rem;
    padding: 1rem;
    border: 1px solid color-mix(in srgb, var(--tone-yarns) 22%, transparent);
    border-radius: 1rem;
    background: color-mix(in srgb, var(--tone-yarns-wash) 55%, transparent);
  }

  .symbol-group {
    display: grid;
    gap: 0.4rem;
  }

  .symbol-label {
    font-size: 0.875rem;
    font-weight: 650;
  }

  .symbol-options {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
  }

  .symbol-options button,
  .read-symbols li {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    padding: 0.35rem 0.65rem;
    border: 1px solid var(--control-border);
    border-radius: 999px;
    color: var(--ink);
  }

  .symbol-options button {
    min-height: 44px;
    background: var(--card-fill);
  }

  .symbol-options button[aria-pressed="true"],
  .read-symbols li {
    border-color: var(--tone-yarns);
    background: var(--tone-yarns-wash);
    color: var(--tone-yarns);
  }

  .symbol-options svg {
    width: 1.25rem;
    height: 1.25rem;
    flex: none;
  }

  .read-symbols {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  textarea {
    width: 100%;
  }

  p {
    margin: 0;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }

  @media print {
    .washing-card {
      padding: 0.25in 0;
      border: 0;
      background: none;
    }
  }
</style>
