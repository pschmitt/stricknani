<script lang="ts">
  import type { Snippet } from "svelte";
  import NavIcon from "./NavIcon.svelte";
  import SectionHeading from "./SectionHeading.svelte";

  type Tone = "projects" | "yarns";

  let {
    id,
    heading,
    tone = "projects",
    href,
    archiveHref,
    openLabel,
    archiveLabel,
    emptyLabel,
    children,
  }: {
    id: string;
    heading: string;
    tone?: Tone;
    href: string | null;
    archiveHref: string | null;
    openLabel: string;
    archiveLabel: string;
    emptyLabel: string;
    children?: Snippet;
  } = $props();
</script>

<!-- The shared source card of project and yarn pages: original link, archived copy and the
     archive control (passed as children), so both pages cannot drift apart. -->
<section
  class="source-card"
  style:--source-tone={`var(--tone-${tone})`}
  aria-labelledby={id}
>
  <SectionHeading {id} icon="link" {tone}>{heading}</SectionHeading>
  {#if href}
    <a class="source-link" {href} target="_blank" rel="noopener noreferrer">
      <span class="source-link__icon" aria-hidden="true"
        ><NavIcon name="notebook" /></span
      >
      {openLabel} ↗
    </a>
  {:else}
    <p class="muted-copy">{emptyLabel}</p>
  {/if}
  {#if archiveHref}
    <a
      class="source-link"
      href={archiveHref}
      target="_blank"
      rel="noopener noreferrer"
    >
      <span class="source-link__icon" aria-hidden="true"
        ><NavIcon name="history" /></span
      >
      {archiveLabel} ↗
    </a>
  {/if}
  {@render children?.()}
</section>

<style>
  .source-card {
    display: grid;
    min-width: 0;
    gap: 0.35rem;
    border: 1px solid var(--card-border);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--card-fill);
  }

  .source-card :global(.section-heading) {
    margin-bottom: 0.9rem;
  }

  .source-card p {
    margin: 0;
    color: var(--ink-muted);
  }

  .source-link {
    display: inline-flex;
    min-height: 44px;
    width: fit-content;
    align-items: center;
    gap: 0.55rem;
    color: var(--accent-strong);
    font-weight: 640;
  }

  .source-link__icon {
    display: inline-grid;
    width: 1.8rem;
    height: 1.8rem;
    flex: none;
    place-items: center;
    border-radius: 999px;
    color: var(--source-tone);
    background: color-mix(in srgb, var(--source-tone) 16%, transparent);
  }

  .source-link__icon :global(svg) {
    width: 1rem;
    height: 1rem;
  }

  @media print {
    .source-card {
      border: 0;
      border-radius: 0;
      padding: 0.25in 0;
    }
  }
</style>
