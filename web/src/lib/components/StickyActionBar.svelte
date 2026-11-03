<script lang="ts">
  import type { Snippet } from "svelte";

  let {
    save,
    cancel,
    status,
  }: {
    save: Snippet;
    cancel: Snippet;
    status?: Snippet;
  } = $props();
</script>

<div class="sticky-action-bar">
  {#if status}
    <div class="sticky-action-bar__status">{@render status()}</div>
  {/if}
  <div class="sticky-action-bar__actions">
    {@render save()}
    {@render cancel()}
  </div>
</div>

<style>
  .sticky-action-bar {
    position: sticky;
    z-index: 4;
    bottom: 0;
    display: flex;
    min-width: 0;
    max-width: 100%;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 0.7rem 1rem;
    box-sizing: border-box;
    border-top: 1px solid var(--line);
    padding: 0.75rem max(1rem, env(safe-area-inset-right)) 0.75rem
      max(1rem, env(safe-area-inset-left));
    background: var(--surface);
  }

  .sticky-action-bar__status {
    min-width: 0;
    color: var(--ink-muted);
  }

  .sticky-action-bar__actions {
    display: flex;
    min-width: 0;
    max-width: 100%;
    align-items: center;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 0.6rem;
  }

  .sticky-action-bar__actions :global(*) {
    max-width: 100%;
  }

  @media (max-width: 760px) {
    .sticky-action-bar {
      bottom: calc(
        var(--app-mobile-nav-height, 57px) + env(safe-area-inset-bottom)
      );
      padding-top: 0.65rem;
      padding-bottom: 0.65rem;
    }
  }
</style>
