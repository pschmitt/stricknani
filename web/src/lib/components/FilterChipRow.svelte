<script lang="ts">
  // The filter row of a library list, like Android's LazyRow of chips: one line that scrolls
  // sideways, with a soft fade on every edge that still hides chips.
  import type { Snippet } from "svelte";

  interface Props {
    label: string;
    testid?: string;
    children: Snippet;
  }

  let { label, testid, children }: Props = $props();

  let row = $state<HTMLDivElement>();
  let fadeStart = $state(false);
  let fadeEnd = $state(false);

  function update() {
    if (!row) return;
    fadeStart = row.scrollLeft > 1;
    fadeEnd = row.scrollLeft + row.clientWidth < row.scrollWidth - 1;
  }

  $effect(() => {
    if (!row) return;
    update();
    const observer = new ResizeObserver(update);
    observer.observe(row);
    for (const child of row.children) observer.observe(child);
    return () => observer.disconnect();
  });
</script>

<div
  bind:this={row}
  class="filter-chip-row"
  class:fade-start={fadeStart}
  class:fade-end={fadeEnd}
  role="group"
  aria-label={label}
  data-testid={testid}
  onscroll={update}
>
  {@render children()}
</div>

<style>
  .filter-chip-row {
    display: flex;
    min-width: 0;
    gap: 0.5rem;
    overflow-x: auto;
    /* Room for the focus ring and the enlarged hit area of the chips. */
    padding: 0.25rem 0.25rem;
    margin-inline: -0.25rem;
    overscroll-behavior-inline: contain;
    scrollbar-width: none;
  }

  .filter-chip-row::-webkit-scrollbar {
    display: none;
  }

  .fade-end {
    mask-image: linear-gradient(
      to right,
      #000 calc(100% - 2.5rem),
      transparent
    );
  }

  .fade-start {
    mask-image: linear-gradient(to right, transparent, #000 2.5rem);
  }

  .fade-start.fade-end {
    mask-image: linear-gradient(
      to right,
      transparent,
      #000 2.5rem,
      #000 calc(100% - 2.5rem),
      transparent
    );
  }
</style>
