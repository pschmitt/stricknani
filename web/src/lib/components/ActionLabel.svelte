<script lang="ts">
  import type { Snippet } from "svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

  type ActionLabelSize = "md" | "sm";

  let {
    icon,
    size = "md",
    children,
  }: {
    icon: NavIconName;
    size?: ActionLabelSize;
    children: Snippet;
  } = $props();
</script>

<span class="action-label" class:action-label--sm={size === "sm"}>
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
    flex: 0 0 var(--ui-action-icon-size-md);
    width: var(--ui-action-icon-size-md);
    height: var(--ui-action-icon-size-md);
    stroke-width: 2;
  }

  .action-label--sm :global(svg) {
    flex-basis: var(--ui-action-icon-size-sm);
    width: var(--ui-action-icon-size-sm);
    height: var(--ui-action-icon-size-sm);
  }
</style>
