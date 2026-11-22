<script lang="ts">
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import SectionHeading from "./SectionHeading.svelte";

  let {
    locale,
    value,
    onInput,
    disabled = false,
    textSizeClass = "",
  }: {
    locale: Locale;
    value: string | null | undefined;
    onInput?: (value: string) => void;
    disabled?: boolean;
    textSizeClass?: string;
  } = $props();

  const headingId = $props.id();
</script>

{#if onInput || value}
  <section class="washing-card" aria-labelledby={headingId}>
    <SectionHeading icon="laundry" tone="yarns" id={headingId}
      >{t("Washing instructions", locale)}</SectionHeading
    >
    {#if onInput}
      <textarea
        aria-labelledby={headingId}
        rows={3}
        {disabled}
        value={value ?? ""}
        oninput={(event) => onInput?.(event.currentTarget.value)}></textarea>
    {:else}
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
