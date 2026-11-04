<script lang="ts">
  import type { Snippet } from "svelte";
  import NavIcon, { type NavIconName } from "./NavIcon.svelte";

  type Tone =
    "home" | "projects" | "yarns" | "create" | "categories" | "gauge" | "audit";

  let {
    icon,
    tone = "projects",
    id,
    children,
  }: {
    icon: NavIconName;
    tone?: Tone;
    id?: string;
    children: Snippet;
  } = $props();
</script>

<!-- A real h2 with a tinted icon chip, the same treatment as Home's section titles. -->
<h2 {id} class="section-heading">
  <span class="section-heading__chip" data-tone={tone} aria-hidden="true"
    ><NavIcon name={icon} /></span
  >
  <span class="section-heading__text">{@render children()}</span>
</h2>

<style>
  .section-heading {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.6rem;
    margin: 0;
    font-size: 1.1rem;
    letter-spacing: -0.02em;
    line-height: 1.3;
  }

  .section-heading__chip {
    --tone: var(--tone-projects);
    --tone-wash: var(--tone-projects-wash);
    display: grid;
    width: 1.9rem;
    height: 1.9rem;
    flex: none;
    place-items: center;
    border-radius: 50%;
    color: var(--tone);
    background: var(--tone-wash);
  }

  .section-heading__chip[data-tone="home"] {
    --tone: var(--tone-home);
    --tone-wash: var(--tone-home-wash);
  }
  .section-heading__chip[data-tone="yarns"] {
    --tone: var(--tone-yarns);
    --tone-wash: var(--tone-yarns-wash);
  }
  .section-heading__chip[data-tone="create"] {
    --tone: var(--tone-create);
    --tone-wash: var(--tone-create-wash);
  }
  .section-heading__chip[data-tone="categories"] {
    --tone: var(--tone-categories);
    --tone-wash: var(--tone-categories-wash);
  }
  .section-heading__chip[data-tone="gauge"] {
    --tone: var(--tone-gauge);
    --tone-wash: var(--tone-gauge-wash);
  }
  .section-heading__chip[data-tone="audit"] {
    --tone: var(--tone-audit);
    --tone-wash: var(--tone-audit-wash);
  }

  .section-heading__chip :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }

  .section-heading__text {
    min-width: 0;
  }
</style>
