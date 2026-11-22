<script lang="ts">
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import {
    dryingOptions,
    washTemperatures,
    type Drying,
    type WashTemperature,
    type WashingSymbols,
  } from "./washing-symbols";
  import FancySelect from "./FancySelect.svelte";
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
  function temperatureLabel(option: WashTemperature): string {
    return option === "hand_wash"
      ? t("Hand wash only", locale)
      : `${option} °C`;
  }

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

{#snippet symbol(option: Drying | "wash" | "hand_wash" | "clear")}
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
    {:else if option === "hand_wash"}
      <path
        d="m3 9 3 11h12l3-11M8 13V7a1 1 0 0 1 2 0v3-6a1 1 0 0 1 2 0v5-3a1 1 0 0 1 2 0v4-2a1 1 0 0 1 2 0v4l-2 4h-3"
      />
    {:else if option === "clear"}
      <path d="M6 12h12" />
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
      <div class="symbol-group">
        <span class="symbol-label">{t("Washing temperature", locale)}</span>
        <FancySelect
          label={t("Washing temperature", locale)}
          value={symbols?.temperature ?? ""}
          options={[
            { value: "", label: t("No selection", locale) },
            ...washTemperatures.map((option) => ({
              value: option,
              label: temperatureLabel(option),
            })),
          ]}
          {disabled}
          onselect={(value) =>
            onSymbols?.({
              ...symbols,
              temperature:
                washTemperatures.find((option) => option === value) ?? null,
            })}
        >
          {#snippet renderIcon(value)}
            <span class="care-icon">
              {@render symbol(
                value === ""
                  ? "clear"
                  : value === "hand_wash"
                    ? "hand_wash"
                    : "wash",
              )}
            </span>
          {/snippet}
        </FancySelect>
      </div>
      <div class="symbol-group">
        <span class="symbol-label">{t("Drying", locale)}</span>
        <FancySelect
          label={t("Drying", locale)}
          value={symbols?.drying ?? ""}
          options={[
            { value: "", label: t("No selection", locale) },
            ...dryingOptions.map((option) => ({
              value: option,
              label: dryingLabel(option),
            })),
          ]}
          {disabled}
          onselect={(value) =>
            onSymbols?.({
              ...symbols,
              drying: dryingOptions.find((option) => option === value) ?? null,
            })}
        >
          {#snippet renderIcon(value)}
            <span class="care-icon">
              {@render symbol(
                dryingOptions.find((option) => option === value) ?? "clear",
              )}
            </span>
          {/snippet}
        </FancySelect>
      </div>
    {:else if symbols?.temperature || symbols?.drying}
      <ul class="symbol-options read-symbols">
        {#if symbols.temperature}<li>
            {@render symbol(
              symbols.temperature === "hand_wash" ? "hand_wash" : "wash",
            )}{temperatureLabel(symbols.temperature)}
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

  .read-symbols li {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    padding: 0.35rem 0.65rem;
    border: 1px solid var(--control-border);
    border-radius: 999px;
    color: var(--ink);
  }

  .read-symbols li {
    border-color: var(--tone-yarns);
    background: var(--tone-yarns-wash);
    color: var(--tone-yarns);
  }

  .care-icon {
    display: grid;
    flex: 0 0 1.75rem;
    width: 1.75rem;
    height: 1.75rem;
    place-items: center;
    border-radius: 0.5rem;
    color: var(--tone-yarns);
    background: var(--tone-yarns-wash);
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
