<script lang="ts">
  import type { Snippet } from "svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

  let { icon, children }: { icon: NavIconName; children: Snippet } = $props();
</script>

<span class="action-label">
  <NavIcon name={icon} />
  <span class="action-label__text">{@render children()}</span>
</span>

<style>
  .action-label {
    display: inline-flex;
    min-width: 0;
    align-items: center;
    justify-content: center;
    gap: 0.45rem;
  }
  .action-label__text {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .action-label :global(svg) {
    flex: 0 0 1em;
    width: 1em;
    height: 1em;
  }
</style>
