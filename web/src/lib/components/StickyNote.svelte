<script lang="ts">
  import type { Snippet } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";

  // The Notes section as a paper sticky note: butter-yellow paper, a push pin,
  // a folded corner and a slight tilt. Only the paper layer is rotated,
  // so the text stays upright and crisp. With `emptyHref` and no content it is
  // the faint dashed "Add a note" invitation instead.
  let {
    title,
    headingId,
    headingLevel = 2,
    emptyHref = "",
    emptyLabel = "",
    children,
  }: {
    title: string;
    headingId: string;
    headingLevel?: 2 | 3;
    emptyHref?: string;
    emptyLabel?: string;
    children?: Snippet;
  } = $props();
</script>

{#if emptyHref}
  <a class="sticky sticky--empty" href={emptyHref}>
    <span class="sticky__paper" aria-hidden="true"></span>
    <span class="sticky__body">
      <span class="sticky__pencil" aria-hidden="true"
        ><NavIcon name="action-update" /></span
      >
      {emptyLabel}
    </span>
  </a>
{:else}
  <section class="sticky" aria-labelledby={headingId}>
    <div class="sticky__paper" aria-hidden="true"></div>
    <div class="sticky__body">
      <svelte:element
        this={`h${headingLevel}`}
        id={headingId}
        class="sticky__title">{title}</svelte:element
      >
      {@render children?.()}
    </div>
  </section>
{/if}

<style>
  .sticky {
    --fold: 1.4rem;
    --tilt: -1.5deg;
    position: relative;
    display: block;
    margin: 1.4rem 0.35rem 1rem;
    color: var(--sticky-ink);
    text-decoration: none;
  }

  .sticky__paper {
    position: absolute;
    inset: 0;
    border-radius: 14px 14px 4px 14px;
    background: linear-gradient(
      315deg,
      transparent var(--fold),
      var(--sticky-paper) var(--fold)
    );
    transform: rotate(var(--tilt));
    filter: drop-shadow(0 1px 1px rgb(var(--sticky-shadow) / 22%))
      drop-shadow(0 6px 8px rgb(var(--sticky-shadow) / 16%))
      drop-shadow(0 14px 18px rgb(var(--sticky-shadow) / 10%));
  }

  /* Push pin: a glossy round head with a soft drop shadow, pressed into the
     top edge of the paper. */
  .sticky__paper::before {
    content: "";
    position: absolute;
    top: -0.55rem;
    left: 50%;
    width: 1.35rem;
    height: 1.35rem;
    border-radius: 50%;
    background:
      radial-gradient(
        circle at 34% 30%,
        rgb(255 255 255 / 75%) 0 14%,
        rgb(255 255 255 / 0%) 32%
      ),
      radial-gradient(
        circle at 50% 50%,
        var(--sticky-tape) 0 55%,
        color-mix(in srgb, var(--sticky-tape) 65%, black) 100%
      );
    box-shadow:
      0 1px 1px rgb(0 0 0 / 35%),
      3px 5px 6px rgb(var(--sticky-shadow) / 30%);
    transform: translateX(-50%);
  }

  /* The folded-over corner. */
  .sticky__paper::after {
    content: "";
    position: absolute;
    right: 0;
    bottom: 0;
    width: calc(var(--fold) * 1.4142);
    height: calc(var(--fold) * 1.4142);
    border-top-left-radius: 3px;
    background: linear-gradient(
      315deg,
      transparent 50%,
      var(--sticky-fold) 50%
    );
  }

  .sticky__body {
    position: relative;
    padding: 1.5rem 1.4rem 1.3rem;
  }

  .sticky__title {
    margin: 0 0 0.5rem;
    color: var(--sticky-ink);
    font-family: var(--font-display);
    font-size: 1.25rem;
    font-weight: 650;
    line-height: 1.2;
  }

  .sticky__body :global(a) {
    color: var(--sticky-link);
  }

  .sticky__body :global(.rich-text) {
    color: var(--sticky-ink);
  }

  .sticky--empty {
    --tilt: -1deg;
    color: var(--ink-muted);
  }

  .sticky--empty .sticky__paper {
    background: none;
    border: 2px dashed var(--control-border);
    border-radius: 14px;
    opacity: 0.75;
    filter: none;
  }

  .sticky--empty .sticky__paper::before,
  .sticky--empty .sticky__paper::after {
    display: none;
  }

  .sticky--empty .sticky__body {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0.55rem;
    min-height: 4.5rem;
    padding-block: 1rem;
    font-family: var(--font-display);
    font-weight: 650;
  }

  .sticky__pencil {
    display: inline-flex;
    width: 1.3rem;
    height: 1.3rem;
  }

  .sticky:focus-visible {
    outline: 2px solid var(--focus);
    outline-offset: 4px;
    border-radius: 14px;
  }

  /* On hover or focus the note straightens and lifts. Skipped entirely when
     the person asks for reduced motion; the tilt itself stays. */
  @media (prefers-reduced-motion: no-preference) {
    .sticky__paper {
      transition:
        transform 220ms cubic-bezier(0.34, 1.4, 0.64, 1),
        filter 220ms ease;
    }

    .sticky:hover .sticky__paper,
    .sticky:focus-within .sticky__paper {
      transform: rotate(0deg) translateY(-3px);
      filter: drop-shadow(0 2px 2px rgb(var(--sticky-shadow) / 24%))
        drop-shadow(0 12px 14px rgb(var(--sticky-shadow) / 20%))
        drop-shadow(0 24px 28px rgb(var(--sticky-shadow) / 14%));
    }

    .sticky--empty:hover .sticky__paper,
    .sticky--empty:focus-visible .sticky__paper {
      filter: none;
      opacity: 1;
    }
  }

  /* Plain on paper: no tilt, tape or shadow. */
  @media print {
    .sticky {
      margin: 1rem 0;
      color: #000;
    }

    .sticky__paper {
      display: none;
    }

    .sticky__body {
      padding: 0;
    }

    .sticky__title,
    .sticky__body :global(.rich-text),
    .sticky__body :global(a) {
      color: #000;
    }

    .sticky--empty {
      display: none;
    }
  }
</style>
