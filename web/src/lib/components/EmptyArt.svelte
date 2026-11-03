<script lang="ts">
  import Mascot from "#lib/components/Mascot.svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

  // The mascot with the screen's icon as a little badge, used by every empty state.
  let {
    icon,
    tone = "yarns",
  }: { icon: NavIconName; tone?: "home" | "projects" | "yarns" } = $props();
</script>

<div class="empty-art" aria-hidden="true">
  <Mascot size={120} bob={true} />
  <span class="empty-art__badge" data-tone={tone}><NavIcon name={icon} /></span>
</div>

<style>
  .empty-art {
    position: relative;
    margin-bottom: 0.4rem;
  }

  .empty-art__badge {
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

  .empty-art__badge[data-tone="home"] {
    --tone: var(--tone-home);
    --tone-wash: var(--tone-home-wash);
  }

  .empty-art__badge[data-tone="projects"] {
    --tone: var(--tone-projects);
    --tone-wash: var(--tone-projects-wash);
  }

  .empty-art__badge :global(svg) {
    width: 1.35rem;
    height: 1.35rem;
  }
</style>
