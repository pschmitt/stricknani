<script lang="ts">
  import FavoriteButton from "#lib/components/FavoriteButton.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import OwnerBadge from "#lib/components/OwnerBadge.svelte";
  import type { ResourceOwner } from "#lib/owner.ts";

  export type LibraryCardKind = "project" | "yarn";

  interface Props {
    kind: LibraryCardKind;
    name: string;
    href: string;
    imageUrl: string | null;
    /** Category (projects) or brand/colourway (yarns). */
    meta?: string | null;
    /** Already localised, e.g. "Updated 3 Nov 2026". */
    updated?: string | null;
    /** Rendered as at most three chips. */
    chips?: string[];
    /** Set only for items the signed-in user does not own. */
    owner?: ResourceOwner | null;
    favorite: boolean;
    favoriteBusy?: boolean;
    locale: "en" | "de";
    onfavorite: () => void;
    class?: string;
  }

  let {
    kind,
    name,
    href,
    imageUrl,
    meta = null,
    updated = null,
    chips = [],
    owner = null,
    favorite,
    favoriteBusy = false,
    locale,
    onfavorite,
    class: className = "",
  }: Props = $props();

  let failedUrl = $state<string | null>(null);
  const showImage = $derived(Boolean(imageUrl) && failedUrl !== imageUrl);
  const shownChips = $derived(chips.filter(Boolean).slice(0, 3));
</script>

<article class={`library-card library-card--${kind} ${className}`}>
  <a class="resource-card__link library-card__link" {href}>
    <span class="library-card__media">
      {#if showImage && imageUrl}
        <img
          src={imageUrl}
          alt=""
          width="400"
          height="300"
          loading="lazy"
          decoding="async"
          onerror={() => (failedUrl = imageUrl)}
        />
      {:else}
        <span class="library-card__placeholder" aria-hidden="true">
          <NavIcon name={kind === "project" ? "projects" : "yarns"} />
        </span>
      {/if}
      {#if owner}
        <span class="library-card__owner"><OwnerBadge {owner} {locale} /></span>
      {/if}
    </span>
    <span class="library-card__copy">
      <h2 class="library-card__title">{name}</h2>
      {#if meta}
        <span class="library-card__meta">{meta}</span>
      {/if}
      {#if updated}
        <span class="library-card__updated">{updated}</span>
      {/if}
      {#if shownChips.length}
        <span class="library-card__chips">
          {#each shownChips as chip, index (`${index}:${chip}`)}
            <span class="library-card__chip">{chip}</span>
          {/each}
        </span>
      {/if}
    </span>
  </a>
  <!-- A sibling of the link, never nested in it. -->
  <span class="library-card__favorite">
    <FavoriteButton
      {favorite}
      busy={favoriteBusy}
      {locale}
      onclick={onfavorite}
    />
  </span>
</article>

<style>
  .library-card {
    --tone: var(--tone-projects);
    --tone-wash: var(--tone-projects-wash);
    position: relative;
    min-width: 0;
    border-bottom: 1px solid var(--line);
    color: var(--ink);
  }

  .library-card--yarn {
    --tone: var(--tone-yarns);
    --tone-wash: var(--tone-yarns-wash);
  }

  .library-card__link {
    display: grid;
    min-width: 0;
    min-height: 104px;
    grid-template-columns: 112px minmax(0, 1fr);
    align-items: center;
    gap: 1rem;
    border-radius: var(--radius-xs);
    padding: 0.7rem 3.4rem 0.7rem 0.25rem;
    color: inherit;
    text-decoration: none;
  }

  .library-card__link:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: 2px;
  }

  .library-card__media {
    position: relative;
    display: block;
    width: 112px;
    aspect-ratio: 4 / 3;
    overflow: hidden;
    border-radius: 14px;
    background: var(--tone-wash);
  }

  .library-card__media img {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .library-card__owner {
    position: absolute;
    inset-block-end: 0.3rem;
    inset-inline-start: 0.3rem;
    display: inline-flex;
  }

  .library-card__placeholder {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: var(--tone);
    background: radial-gradient(
      120% 120% at 20% 10%,
      color-mix(in srgb, var(--surface) 55%, var(--tone-wash)),
      var(--tone-wash)
    );
  }

  .library-card__placeholder :global(svg) {
    width: 2.2rem;
    height: 2.2rem;
  }

  .library-card__copy {
    display: grid;
    min-width: 0;
    gap: 0.2rem;
  }

  .library-card__title {
    display: -webkit-box;
    overflow: hidden;
    margin: 0;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    font-family: var(--font-display);
    font-size: 1.15rem;
    font-weight: 650;
    line-height: 1.25;
    overflow-wrap: anywhere;
  }

  .library-card__meta,
  .library-card__updated {
    overflow: hidden;
    color: var(--ink-muted);
    font-size: 0.88rem;
    line-height: 1.35;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .library-card__chips {
    display: none;
  }

  .library-card__favorite {
    position: absolute;
    inset-block-start: 50%;
    inset-inline-end: 0;
    translate: 0 -50%;
    display: inline-flex;
  }

  /* Phones: the Android row - a bordered 16px card on the canvas, 14px padding all round, a 112x84
     thumbnail and a bare heart. */
  @media (max-width: 47.999rem) {
    .library-card {
      border: 1px solid var(--line);
      border-radius: 16px;
    }

    .library-card__link {
      min-height: 0;
      gap: 14px;
      border-radius: 16px;
      padding: 14px 3.6rem 14px 14px;
    }

    .library-card__favorite {
      inset-inline-end: 0.4rem;
    }

    .library-card__favorite :global(.favorite-button.ui-button) {
      border-color: transparent;
      background: transparent;
    }
  }

  @container library-grid (min-width: 720px) {
    .library-card {
      border: 1px solid var(--line);
      border-radius: 16px;
      background: var(--surface);
    }

    .library-card__link {
      display: flex;
      height: 100%;
      min-height: 0;
      flex-direction: column;
      align-items: stretch;
      gap: 0;
      border-radius: 16px;
      padding: 0;
    }

    .library-card__media {
      width: 100%;
      border-radius: 15px 15px 0 0;
    }

    .library-card__placeholder :global(svg) {
      width: 3.6rem;
      height: 3.6rem;
      opacity: 0.85;
    }

    .library-card__copy {
      flex: 1;
      align-content: start;
      gap: 0.3rem;
      padding: 0.95rem 1.05rem 1.1rem;
    }

    .library-card__title {
      display: -webkit-box;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
      line-clamp: 2;
      font-size: 1.2rem;
      white-space: normal;
      overflow-wrap: anywhere;
    }

    .library-card__meta,
    .library-card__updated {
      font-size: 0.88rem;
    }

    .library-card__updated {
      font-size: 0.8rem;
      opacity: 0.85;
    }

    .library-card__chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
      margin-top: 0.45rem;
    }

    .library-card__chip {
      max-width: 100%;
      overflow: hidden;
      border-radius: 999px;
      padding: 0.15rem 0.65rem;
      color: var(--tone);
      background: var(--tone-wash);
      font-size: 0.78rem;
      font-weight: 650;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .library-card__favorite {
      inset-block-start: 0.6rem;
      inset-inline-end: 0.6rem;
      translate: none;
    }

    .library-card__favorite :global(.favorite-button.ui-button) {
      border-color: transparent;
      border-radius: 999px;
      background: color-mix(in srgb, var(--surface) 88%, transparent);
      box-shadow: 0 2px 8px rgb(0 0 0 / 18%);
      backdrop-filter: blur(6px);
    }
  }

  @media (prefers-reduced-motion: no-preference) {
    .library-card__media img {
      transition: transform 280ms ease;
    }

    @container library-grid (min-width: 720px) {
      .library-card {
        transition:
          transform 200ms ease,
          box-shadow 200ms ease;
      }

      .library-card:hover {
        transform: translateY(-2px);
        box-shadow: var(--shadow);
      }

      .library-card:hover .library-card__media img {
        transform: scale(1.03);
      }
    }
  }
</style>
