<script lang="ts">
  // One filter chip, the web twin of Android's Material 3 FilterChip: a compact pill that is
  // outlined while off and a filled teal container with a bold label while on (no check mark).
  // It is a toggle button (`aria-pressed`); chips sit in a FilterChipRow.
  import type { Snippet } from "svelte";

  interface Props {
    label: string;
    selected: boolean;
    onclick: () => void;
    testid?: string;
    /** An avatar or icon before the label (Android's leadingIcon). */
    leading?: Snippet;
  }

  let { label, selected, onclick, testid, leading }: Props = $props();
</script>

<button
  class="filter-chip"
  class:filter-chip--selected={selected}
  type="button"
  aria-pressed={selected}
  data-testid={testid}
  title={label}
  {onclick}
>
  {#if leading}{@render leading()}{/if}
  <span class="filter-chip__label" data-label={label}>{label}</span>
</button>

<style>
  .filter-chip {
    position: relative;
    display: inline-flex;
    box-sizing: border-box;
    min-height: 36px;
    max-width: 18rem;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    border: 1px solid var(--control-border);
    border-radius: 999px;
    padding: 0 0.85rem;
    color: var(--ink-muted);
    background: transparent;
    font: inherit;
    font-size: 0.875rem;
    font-weight: 400;
    line-height: 1.25;
    cursor: pointer;
    transition: background-color 120ms ease;
  }

  /* The visible chip is 36px; the hit area (padding box plus 5px each side) reaches 44px without moving the layout. */
  .filter-chip::after {
    position: absolute;
    inset: -5px -2px;
    content: "";
  }

  .filter-chip:hover:not(.filter-chip--selected) {
    background: color-mix(in srgb, var(--ink) 8%, transparent);
  }

  .filter-chip:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .filter-chip--selected {
    border-color: transparent;
    color: var(--on-lagoon-container);
    background: var(--lagoon-container);
    font-weight: 700;
  }

  .filter-chip--selected:hover {
    background: color-mix(
      in srgb,
      var(--on-lagoon-container) 8%,
      var(--lagoon-container)
    );
  }

  .filter-chip__label {
    display: inline-flex;
    min-width: 0;
    flex-direction: column;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  /* Reserve the bold width so a chip does not grow when it is selected. */
  .filter-chip__label::after {
    height: 0;
    overflow: hidden;
    visibility: hidden;
    content: attr(data-label);
    font-weight: 700;
    pointer-events: none;
  }

  .filter-chip :global(svg) {
    width: 1.125rem;
    height: 1.125rem;
    flex: none;
  }

  @media (prefers-reduced-motion: reduce) {
    .filter-chip {
      transition: none;
    }
  }
</style>
