<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { swatchPath } from "./knitted-swatch";
  import {
    defaultSwatchSize,
    sizeLabel,
    swatchAspect,
    type SwatchSize,
  } from "./swatch-size";

  /**
   * A tiny 10 × 10 cm stockinette square: one "V" per stitch, as many columns and rows as
   * typed (capped), so a tighter gauge visibly looks denser. Mirrors the Android gauge
   * calculator. Decorative: the numbers are in the inputs next to it.
   */
  let {
    stitches,
    rows,
    size = defaultSwatchSize(),
    changeLabel,
    onchange,
  }: {
    stitches: number | string | undefined;
    rows: number | string | undefined;
    /** Measured area in cm; the drawing follows its aspect ratio. */
    size?: SwatchSize;
    /** Accessible name prefix for the caption button; the button only exists with `onchange`. */
    changeLabel?: string;
    onchange?: () => void;
  } = $props();

  const swatch = $derived(swatchPath(stitches, rows));
  const aspect = $derived(swatchAspect(size));
  const caption = $derived(sizeLabel(size));
</script>

<figure class="knitted-swatch">
  <svg
    viewBox="0 0 100 100"
    preserveAspectRatio={swatch ? "none" : "xMidYMid meet"}
    focusable="false"
    aria-hidden="true"
    style:--swatch-w={aspect.w}
    style:--swatch-h={aspect.h}
  >
    {#if swatch}
      <path
        class="knitted-swatch__stitches knitted-swatch__stitches--odd"
        d={swatch.odd}
        stroke-width={swatch.strokeWidth}
      />
      <path
        class="knitted-swatch__stitches"
        d={swatch.even}
        stroke-width={swatch.strokeWidth}
      />
    {:else}
      <text x="50" y="50" class="knitted-swatch__empty">?</text>
    {/if}
  </svg>
  {#if onchange}
    <button
      type="button"
      class="size-button"
      aria-label={`${changeLabel ?? ""}: ${caption}`}
      onclick={onchange}
    >
      <span>{caption}</span>
      <NavIcon name="edit" />
    </button>
  {:else}
    <figcaption aria-hidden="true">{caption}</figcaption>
  {/if}
</figure>

<style>
  .knitted-swatch {
    display: grid;
    justify-items: center;
    gap: 0.3rem;
    margin: 0;
  }
  svg {
    box-sizing: content-box;
    width: calc(6.5rem * var(--swatch-w, 1));
    height: calc(6.5rem * var(--swatch-h, 1));
    margin-block: calc((6.5rem - 6.5rem * var(--swatch-h, 1)) / 2);
    padding: 0.4rem;
    border-radius: var(--radius-sm, 16px);
    background: color-mix(in srgb, currentColor 14%, transparent);
  }
  .knitted-swatch__stitches {
    fill: none;
    stroke: currentColor;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .knitted-swatch__stitches--odd {
    opacity: 0.75;
  }
  .knitted-swatch__empty {
    fill: currentColor;
    font: 700 2.4rem var(--font-display, serif);
    text-anchor: middle;
    dominant-baseline: central;
  }
  .size-button {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
    min-height: 44px;
    border: 0;
    border-radius: var(--radius-xs);
    padding: 0 0.5rem;
    color: inherit;
    background: transparent;
    font: inherit;
    font-size: 0.75rem;
    font-weight: 700;
    cursor: pointer;
  }
  .size-button:hover {
    background: color-mix(in srgb, currentColor 12%, transparent);
  }
  .size-button:focus-visible {
    outline: 2px solid currentColor;
    outline-offset: 2px;
  }
  .size-button :global(svg) {
    width: 0.95rem;
    height: 0.95rem;
    padding: 0;
    margin: 0;
    background: none;
  }
  figcaption {
    font-size: 0.75rem;
    font-weight: 700;
  }
</style>
