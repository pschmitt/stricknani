<script lang="ts">
  import type { Snippet } from "svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

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
  <div class="empty-state__art">
    <Mascot size={120} bob={true} />
    <span class="empty-state__badge" data-tone={tone}
      ><NavIcon name={icon} /></span
    >
  </div>
  <svelte:element this={headingTag} class="empty-state__title"
    >{title}</svelte:element
  >
  {#if hint}<p class="empty-state__hint">{hint}</p>{/if}
  {#if children}<div class="empty-state__actions">
      {@render children()}
    </div>{/if}
</div>

<style>
  .empty-state {
    display: grid;
    justify-items: center;
    gap: 0.5rem;
    border-radius: var(--radius-lg);
    padding: clamp(1.5rem, 5vw, 2.75rem) 1.25rem;
    text-align: center;
  }

  .empty-state__art {
    position: relative;
    margin-bottom: 0.4rem;
  }

  .empty-state__badge {
    --tone: var(--tone-yarns);
    --tone-wash: var(--tone-yarns-wash);
    position: absolute;
    right: -0.2rem;
    bottom: 0;
    display: grid;
    width: 2.75rem;
    height: 2.75rem;
    place-items: center;
    border: 3px solid var(--canvas);
    border-radius: 50%;
    color: var(--tone);
    background: var(--tone-wash);
    box-shadow: 0 2px 6px rgb(63 20 80 / 18%);
  }

  .empty-state__badge[data-tone="home"] {
    --tone: var(--tone-home);
    --tone-wash: var(--tone-home-wash);
  }
  .empty-state__badge[data-tone="projects"] {
    --tone: var(--tone-projects);
    --tone-wash: var(--tone-projects-wash);
  }

  .empty-state__badge :global(svg) {
    width: 1.35rem;
    height: 1.35rem;
  }

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
