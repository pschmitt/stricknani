<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";

  export type ResourceCardKind = "project" | "yarn" | "category" | "resource";
  export type ResourceCardVariant = "compact" | "library";
  export type ResourceCardTitleTag = "h1" | "h2" | "h3" | "span";

  interface Props {
    kind: ResourceCardKind;
    name: string;
    href: string | null;
    imageUrl: string | null;
    subtitle?: string | null;
    meta?: string[];
    badge?: string | null;
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

<div class="resource-card" class:library={variant === "library"}>
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
    border-radius: 0.65rem;
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
    border-radius: 0.55rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
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
    font-weight: 680;
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
    border-radius: 0;
  }

  .library .resource-card__image {
    width: 100%;
    min-height: 9.5rem;
    aspect-ratio: 1.4;
    border-radius: 0;
    color: color-mix(in srgb, var(--accent) 60%, transparent);
    background: var(--accent-wash);
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
    font-size: 1.08rem;
    letter-spacing: -0.025em;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .library .resource-card__subtitle,
  .library .resource-card__meta {
    font-size: 0.88rem;
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
