<script lang="ts">
  import type { Snippet } from "svelte";
  import EmptyArt from "#lib/components/EmptyArt.svelte";
  import type { NavIconName } from "#lib/components/NavIcon.svelte";

  // "This list has nothing in it right now": the mascot with the screen's icon as a little
  // badge, a friendly title and a hint, plus an optional action.
  let {
    icon,
    title,
    hint = "",
    tone = "yarns",
    headingTag = "h2",
    children,
  }: {
    icon: NavIconName;
    title: string;
    hint?: string;
    tone?: "home" | "projects" | "yarns";
    headingTag?: "h2" | "h3" | "p";
    children?: Snippet;
  } = $props();
</script>

<div class="empty-state">
  <EmptyArt {icon} {tone} />
  <svelte:element this={headingTag} class="empty-state__title"
    >{title}</svelte:element
  >
  {#if hint}<p class="empty-state__hint">{hint}</p>{/if}
  {#if children}<div class="empty-state__actions">
      {@render children()}
    </div>{/if}
</div>

<style>
  .empty-state__title {
    margin: 0;
    font-size: 1.45rem;
    line-height: 1.25;
  }

  .empty-state__hint {
    max-width: 22rem;
    margin: 0;
    color: var(--ink-muted);
  }

  .empty-state__actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0.6rem;
    margin-top: 0.8rem;
  }
</style>
