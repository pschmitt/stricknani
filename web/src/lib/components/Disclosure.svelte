<script lang="ts">
  import type { Snippet } from "svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

  let {
    label,
    icon,
    tone = "projects",
    open = $bindable(false),
    element = $bindable(null),
    class: className = "",
    children,
  }: {
    label: string;
    icon: NavIconName;
    tone?: "projects" | "yarns" | "gauge";
    open?: boolean;
    element?: HTMLDetailsElement | null;
    class?: string;
    children?: Snippet;
  } = $props();
</script>

<details class="disclosure {className}" bind:open bind:this={element}>
  <summary class="disclosure__summary">
    <span class="disclosure__icon" data-tone={tone} aria-hidden="true"
      ><NavIcon name={icon} /></span
    >
    <span class="disclosure__label">{label}</span>
    <span class="disclosure__chevron"><NavIcon name="chevron-down" /></span>
  </summary>
  {@render children?.()}
</details>

<style>
  .disclosure {
    border: 1px solid var(--card-border);
    border-radius: var(--radius-sm);
    background: var(--card-fill);
    overflow: hidden;
  }

  .disclosure__summary {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    min-height: 56px;
    padding: 0.5rem clamp(1rem, 3vw, 1.5rem);
    color: var(--ink);
    font-family: var(--font-display);
    font-size: 1.1rem;
    font-weight: 650;
    letter-spacing: -0.02em;
    line-height: 1.3;
    cursor: pointer;
    list-style: none;
  }

  .disclosure__summary::marker {
    content: "";
  }

  .disclosure__summary::-webkit-details-marker {
    display: none;
  }

  .disclosure__summary:hover {
    background: color-mix(in srgb, var(--accent-strong) 8%, transparent);
  }

  .disclosure__summary:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: -3px;
  }

  .disclosure__chevron {
    display: inline-flex;
    flex: none;
    color: var(--accent-strong);
  }

  /* The same round tinted chip as SectionHeading, so collapsible and plain headers match. */
  .disclosure__icon {
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

  .disclosure__icon[data-tone="yarns"] {
    --tone: var(--tone-yarns);
    --tone-wash: var(--tone-yarns-wash);
  }

  .disclosure__icon[data-tone="gauge"] {
    --tone: var(--tone-gauge);
    --tone-wash: var(--tone-gauge-wash);
  }

  .disclosure__icon :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }

  .disclosure__chevron :global(svg) {
    width: 1.35rem;
    height: 1.35rem;
  }

  .disclosure__label {
    flex: 1;
    min-width: 0;
  }

  .disclosure[open] .disclosure__chevron :global(svg) {
    transform: rotate(180deg);
  }

  @media (prefers-reduced-motion: no-preference) {
    .disclosure__chevron :global(svg) {
      transition: transform 0.2s ease;
    }
  }
</style>
