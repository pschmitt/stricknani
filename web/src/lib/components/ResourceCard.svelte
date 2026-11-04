<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import OwnerBadge from "#lib/components/OwnerBadge.svelte";
  import type { ResourceOwner } from "#lib/owner.ts";

  export type ResourceCardKind = "project" | "yarn" | "category" | "resource";
  export type ResourceCardVariant = "compact" | "library" | "library-row";
  export type ResourceCardTitleTag = "h1" | "h2" | "h3" | "span";

  interface Props {
    kind: ResourceCardKind;
    name: string;
    href: string | null;
    imageUrl: string | null;
    subtitle?: string | null;
    meta?: string[];
    badge?: string | null;
    /** Set only for items the signed-in user does not own. */
    owner?: ResourceOwner | null;
    variant?: ResourceCardVariant;
    titleTag?: ResourceCardTitleTag;
    titleId?: string;
  }

  let {
    kind,
    name,
    href,
    imageUrl,
    subtitle = null,
    meta = [],
    badge = null,
    owner = null,
    variant = "compact",
    titleTag = "span",
    titleId,
  }: Props = $props();

  let imageState = $state<{ url: string | null; failed: boolean }>({
    url: null,
    failed: false,
  });

  const iconName = $derived(
    kind === "project"
      ? "projects"
      : kind === "yarn"
        ? "yarns"
        : kind === "category"
          ? "categories"
          : "audit",
  );
  const showImage = $derived(
    Boolean(imageUrl) && imageState.url === imageUrl && !imageState.failed,
  );

  $effect(() => {
    const nextUrl = imageUrl;
    const previousUrl = imageState.url;
    if (nextUrl !== previousUrl) {
      const nextState = { url: nextUrl, failed: false };
      imageState = nextState;
    }
  });

  function handleImageError(event: Event) {
    const target = event.currentTarget;
    if (
      target instanceof HTMLImageElement &&
      target.getAttribute("src") === imageUrl &&
      imageUrl
    ) {
      imageState = { url: imageUrl, failed: true };
    }
  }
</script>

{#snippet cardBody()}
  <span class="resource-card__image">
    {#if showImage && imageUrl}
      <img
        src={imageUrl}
        alt=""
        loading="lazy"
        decoding="async"
        onerror={handleImageError}
      />
    {:else}
      <NavIcon name={iconName} />
    {/if}
    {#if owner}
      <span class="resource-card__owner"><OwnerBadge {owner} /></span>
    {/if}
    {#if variant === "library" && badge}
      <span class="resource-card__badge">{badge}</span>
    {/if}
  </span>
  <div class="resource-card__copy">
    <svelte:element this={titleTag} id={titleId} class="resource-card__name">
      {name}
    </svelte:element>
    {#if subtitle}
      <span class="resource-card__subtitle">{subtitle}</span>
    {/if}
    {#if meta.length}
      <span class="resource-card__meta">
        {#each meta as item, index (`${index}:${item}`)}
          <span>{item}</span>
        {/each}
      </span>
    {/if}
  </div>
{/snippet}

<div
  class="resource-card"
  class:library={variant === "library"}
  class:library-row={variant === "library-row"}
>
  {#if href}
    <a class="resource-card__link" {href}>
      {@render cardBody()}
    </a>
  {:else}
    <div class="resource-card__link">
      {@render cardBody()}
    </div>
  {/if}
</div>

<style>
  .resource-card {
    min-width: 0;
    color: var(--ink);
  }

  .resource-card__link {
    display: grid;
    min-width: 0;
    grid-template-columns: 3.25rem minmax(0, 1fr);
    align-items: center;
    gap: 0.7rem;
    border-radius: var(--radius-xs);
    color: inherit;
    text-decoration: none;
  }

  .resource-card__link[href]:hover .resource-card__name {
    color: var(--accent-strong);
    text-decoration: underline;
    text-underline-offset: 0.14em;
  }

  .resource-card__link[href]:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: 3px;
  }

  .resource-card__image {
    position: relative;
    display: grid;
    width: 3.25rem;
    aspect-ratio: 1;
    flex: none;
    place-items: center;
    overflow: hidden;
    border-radius: var(--radius-xs);
    color: var(--on-primary-container);
    background: var(--primary-container);
  }

  .resource-card__image :global(svg) {
    width: 1.4rem;
    height: 1.4rem;
  }

  .resource-card__image img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .resource-card__copy {
    display: grid;
    min-width: 0;
    gap: 0.15rem;
  }

  .resource-card__name {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
    font: inherit;
    font-weight: 800;
  }

  .resource-card__subtitle,
  .resource-card__meta {
    min-width: 0;
    color: var(--ink-muted);
    font-size: 0.84rem;
    line-height: 1.35;
    overflow-wrap: anywhere;
  }

  .resource-card__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.2rem 0.65rem;
  }

  .library .resource-card__link {
    display: grid;
    align-items: stretch;
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    background: var(--surface);
    transition: transform 200ms cubic-bezier(0.3, 1.6, 0.5, 1);
  }

  .library .resource-card__link[href]:hover {
    transform: translateY(-3px);
  }

  .library .resource-card__image {
    width: 100%;
    min-height: 9.5rem;
    aspect-ratio: 1.4;
    border-radius: 0;
    color: var(--on-primary-container);
    background: var(--primary-container);
  }

  .library .resource-card__image :global(svg) {
    width: 2.8rem;
    height: 2.8rem;
  }

  .library .resource-card__copy {
    gap: 0.35rem;
    padding: 0.9rem 1rem 1rem;
  }

  .library .resource-card__name {
    overflow: hidden;
    font-family: var(--font-display);
    font-size: 1.15rem;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .library .resource-card__subtitle,
  .library .resource-card__meta {
    font-size: 0.88rem;
  }

  .library-row .resource-card__link {
    min-height: 56px;
    grid-template-columns: 56px minmax(0, 1fr);
    align-items: center;
    gap: 0.75rem;
    overflow: visible;
    border-radius: var(--radius-xs);
  }

  .library-row .resource-card__image {
    width: 56px;
    height: 56px;
    min-height: 56px;
    aspect-ratio: 1;
    border-radius: var(--radius-xs);
  }

  .library-row .resource-card__image :global(svg) {
    width: 1.5rem;
    height: 1.5rem;
  }

  .library-row .resource-card__copy {
    min-width: 0;
    gap: 0.1rem;
    padding: 0;
  }

  .library-row .resource-card__name {
    overflow: hidden;
    font-size: 0.98rem;
    letter-spacing: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .library-row .resource-card__subtitle,
  .library-row .resource-card__meta {
    display: block;
    overflow: hidden;
    font-size: 0.76rem;
    line-height: 1.3;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .resource-card__owner {
    position: absolute;
    inset-block-end: 0.2rem;
    inset-inline-end: 0.2rem;
    display: inline-flex;
  }

  .resource-card__badge {
    position: absolute;
    bottom: 0.65rem;
    left: 0.65rem;
    max-width: calc(100% - 1.3rem);
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.25rem 0.65rem;
    color: var(--ink);
    background: var(--surface);
    font-size: 0.78rem;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  @media (prefers-reduced-motion: no-preference) {
    .resource-card__link[href] .resource-card__name {
      transition: color 120ms ease;
    }
  }
</style>
