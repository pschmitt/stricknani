<script module lang="ts">
  export interface PhotoStripItem {
    id: number;
    url: string;
    alt: string;
  }
</script>

<script lang="ts" generics="Item extends PhotoStripItem">
  import type { Snippet } from "svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import NavIcon from "#lib/components/NavIcon.svelte";

  interface Props {
    items: Item[];
    label: string;
    /** Accessible name of a tile button; the item alt text is appended. */
    openLabel: string;
    locale: "en" | "de";
    onopen: (item: Item, index: number) => void;
    /** Extra per-tile content such as an OCR control. */
    tile?: Snippet<[Item]>;
    /** Render a hidden full-size copy per tile for the print stylesheet. */
    printable?: boolean;
    onprinterror?: (event: Event) => void;
  }

  let {
    items,
    label,
    openLabel,
    locale,
    onopen,
    tile,
    printable = false,
    onprinterror,
  }: Props = $props();

  let track = $state<HTMLElement | null>(null);
  let canPrev = $state(false);
  let canNext = $state(false);

  function measure() {
    if (!track) return;
    const max = track.scrollWidth - track.clientWidth;
    const pos = Math.abs(track.scrollLeft);
    canPrev = pos > 2;
    canNext = max - pos > 2;
  }

  $effect(() => {
    const element = track;
    void items.length;
    if (!element) return;
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  });

  function scrollByTile(direction: -1 | 1) {
    if (!track) return;
    const first = track.querySelector<HTMLElement>(".photo-strip__tile");
    const step = (first?.offsetWidth ?? track.clientWidth * 0.8) + 14;
    const reduced = window.matchMedia(
      "(prefers-reduced-motion: reduce)",
    ).matches;
    track.scrollBy({
      left: direction * step,
      behavior: reduced ? "auto" : "smooth",
    });
  }

  function onkeydown(event: KeyboardEvent) {
    if (event.target !== track) return;
    if (event.key === "ArrowRight") {
      event.preventDefault();
      scrollByTile(1);
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      scrollByTile(-1);
    }
  }
</script>

{#if items.length > 0}
  <section class="photo-strip" aria-label={label} data-count={items.length}>
    <div class="photo-strip__frame">
      <!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
      <ul
        class="photo-strip__track"
        class:photo-strip__track--single={items.length === 1}
        bind:this={track}
        tabindex={items.length > 1 ? 0 : -1}
        aria-label={label}
        onscroll={measure}
        {onkeydown}
      >
        {#each items as item, index (item.id)}
          <li class="photo-strip__tile">
            <button
              type="button"
              class="photo-strip__open"
              aria-label={`${openLabel}: ${item.alt}`}
              onclick={() => onopen(item, index)}
            >
              <img
                class="screen-reference-image"
                src={item.url}
                alt={item.alt}
                width="400"
                height="300"
                loading="lazy"
                decoding="async"
              />
              {#if printable}
                <img
                  class="print-reference-image photo-strip__print"
                  data-print-src={item.url}
                  alt={item.alt}
                  loading="lazy"
                  onerror={onprinterror}
                />
              {/if}
            </button>
            {#if tile}{@render tile(item)}{/if}
          </li>
        {/each}
      </ul>
      {#if items.length > 1}
        <button
          type="button"
          class="photo-strip__arrow photo-strip__arrow--prev"
          aria-label={t("Previous", locale)}
          hidden={!canPrev}
          onclick={() => scrollByTile(-1)}
        >
          <NavIcon name="previous" />
        </button>
        <button
          type="button"
          class="photo-strip__arrow photo-strip__arrow--next"
          aria-label={t("Next", locale)}
          hidden={!canNext}
          onclick={() => scrollByTile(1)}
        >
          <NavIcon name="next" />
        </button>
      {/if}
    </div>
  </section>
{/if}

<style>
  .photo-strip {
    min-width: 0;
    container-type: inline-size;
    container-name: photo-strip;
  }

  .photo-strip__frame {
    position: relative;
    min-width: 0;
  }

  .photo-strip__track {
    display: flex;
    min-width: 0;
    align-items: flex-start;
    gap: 14px;
    margin: 0;
    padding: 0;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    list-style: none;
    scroll-snap-type: x mandatory;
    scrollbar-width: thin;
    border-radius: 16px;
  }

  .photo-strip__track:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: 3px;
  }

  .photo-strip__tile {
    display: grid;
    min-width: 0;
    flex: 0 0 85%;
    gap: 0.4rem;
    scroll-snap-align: start;
  }

  .photo-strip__track--single .photo-strip__tile {
    flex-basis: 100%;
  }

  .photo-strip__open {
    position: relative;
    display: block;
    width: 100%;
    aspect-ratio: 4 / 3;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 16px;
    padding: 0;
    background: var(--surface-raised);
    cursor: zoom-in;
  }

  .photo-strip__open:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: 2px;
  }

  .photo-strip__open img {
    position: absolute;
    inset: 0;
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .photo-strip__open .photo-strip__print {
    display: none;
  }

  .photo-strip__arrow {
    position: absolute;
    top: 50%;
    display: none;
    width: 44px;
    height: 44px;
    place-items: center;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0;
    color: var(--ink);
    background: color-mix(in srgb, var(--surface) 92%, transparent);
    box-shadow: 0 2px 10px rgb(0 0 0 / 20%);
    translate: 0 -50%;
    cursor: pointer;
    opacity: 0;
  }

  .photo-strip__arrow[hidden] {
    display: none !important;
  }

  .photo-strip__arrow--prev {
    inset-inline-start: 8px;
  }

  .photo-strip__arrow--next {
    inset-inline-end: 8px;
  }

  .photo-strip__arrow:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: 2px;
    opacity: 1;
  }

  @container photo-strip (min-width: 720px) {
    .photo-strip__tile,
    .photo-strip__track--single .photo-strip__tile {
      flex: 0 0 clamp(340px, 34cqw, 420px);
    }
  }

  @media (hover: hover) {
    .photo-strip__arrow {
      display: grid;
    }

    .photo-strip__frame:hover .photo-strip__arrow,
    .photo-strip__frame:focus-within .photo-strip__arrow {
      opacity: 1;
    }
  }

  @media (prefers-reduced-motion: no-preference) {
    .photo-strip__track {
      scroll-behavior: smooth;
    }

    .photo-strip__arrow {
      transition: opacity 150ms ease;
    }

    .photo-strip__open img {
      transition: transform 280ms ease;
    }

    .photo-strip__open:hover img {
      transform: scale(1.03);
    }
  }

  @media print {
    .photo-strip__track {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.16in;
      overflow: visible;
    }

    .photo-strip__tile {
      break-inside: avoid;
    }

    .photo-strip__open {
      aspect-ratio: auto;
      overflow: visible;
      border: 0;
      border-radius: 0;
    }

    .photo-strip__open img {
      position: static;
      height: auto;
      max-height: 7in;
      object-fit: contain;
    }

    .photo-strip__arrow {
      display: none !important;
    }

    .photo-strip__open .screen-reference-image {
      display: none !important;
    }

    .photo-strip__open .photo-strip__print {
      display: block !important;
    }
  }
</style>
