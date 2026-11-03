<script lang="ts">
  import { swatchPath } from "./knitted-swatch";

  /**
   * A tiny 10 × 10 cm stockinette square: one "V" per stitch, as many columns and rows as
   * typed (capped), so a tighter gauge visibly looks denser. Mirrors the Android gauge
   * calculator. Decorative: the numbers are in the inputs next to it.
   */
  let {
    stitches,
    rows,
  }: {
    stitches: number | string | undefined;
    rows: number | string | undefined;
  } = $props();

  const swatch = $derived(swatchPath(stitches, rows));
</script>

<figure class="knitted-swatch" aria-hidden="true">
  <svg viewBox="0 0 100 100" focusable="false">
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
  <figcaption>10 × 10 cm</figcaption>
</figure>

<style>
  .knitted-swatch {
    display: grid;
    justify-items: center;
    gap: 0.3rem;
    margin: 0;
  }
  svg {
    width: 6.5rem;
    height: 6.5rem;
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
  figcaption {
    font-size: 0.75rem;
    font-weight: 700;
  }
</style>
