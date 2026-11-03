<script lang="ts">
  import type { Snippet } from "svelte";

  let {
    children,
    busy = false,
    class: className = "",
  }: { children: Snippet; busy?: boolean; class?: string } = $props();
</script>

<!--
  The outer box is the size container that LibraryCard queries: from about
  720px of available width the list becomes a grid of cards, below it stays a
  compact list.
-->
<div class="library-grid" aria-live="polite" aria-busy={busy || undefined}>
  <div class={`library-grid__items ${className}`}>
    {@render children()}
  </div>
</div>

<style>
  .library-grid {
    min-width: 0;
    container-type: inline-size;
    container-name: library-grid;
  }

  .library-grid__items {
    display: grid;
    min-width: 0;
    gap: 0;
  }

  @media (max-width: 47.999rem) {
    .library-grid__items {
      gap: 12px;
    }
  }

  @container library-grid (min-width: 720px) {
    .library-grid__items {
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 20px;
      padding-block: 0.25rem;
    }
  }
</style>
