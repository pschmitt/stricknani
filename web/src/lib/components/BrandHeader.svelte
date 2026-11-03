<script lang="ts">
  import BrandMark from "#lib/components/BrandMark.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { appVersion } from "#lib/app-version.ts";

  let {
    href = "/app/",
    parentHref = null,
    parentLabel = "Back",
    reserveParentSlot = false,
  }: {
    href?: string;
    parentHref?: string | null;
    parentLabel?: string;
    reserveParentSlot?: boolean;
  } = $props();

  // One widget: on top-level pages the lockup links home; on nested pages the
  // whole lockup (chevron, mark and name) links to the parent route. The
  // chevron lives in a fixed column left of the mark, so the mark never moves.
  const parent = $derived(reserveParentSlot && parentHref ? parentHref : null);
</script>

<div class="brand-header" class:with-parent-column={reserveParentSlot}>
  <a
    class="brand-lockup"
    class:parent-link={parent !== null}
    href={parent ?? href}
    aria-label={parent ? parentLabel : "Stricknani"}
  >
    {#if reserveParentSlot}
      <span class="brand-chevron" aria-hidden="true">
        <NavIcon name="previous" />
      </span>
    {/if}
    <span class="brand-mark-wrap"><BrandMark /></span>
    <span class="brand-copy">
      <span class="brand-title">Stricknani</span>
      <span class="brand-version">{appVersion}</span>
    </span>
  </a>
</div>

<style>
  .brand-header {
    /* Geometry of the lockup. --brand-align is where the mark's left edge
       sits relative to the header's content box; on desktop it lines the
       mark up with the rail's nav icons. */
    --brand-pad: 0.3rem;
    --brand-chevron: 0.75rem;
    --brand-align: 0.5rem;
    --brand-ease: cubic-bezier(0.2, 0.8, 0.2, 1);

    display: flex;
    min-width: 0;
    min-height: 44px;
    align-items: center;
  }

  .brand-lockup {
    position: relative;
    isolation: isolate;
    display: inline-flex;
    min-width: 0;
    min-height: 44px;
    align-items: center;
    gap: 0.6rem;
    margin-left: calc(var(--brand-align) - var(--brand-pad));
    border-radius: 0.75rem;
    padding: 0 0.65rem 0 var(--brand-pad);
    color: var(--ink);
    text-decoration: none;
    -webkit-tap-highlight-color: transparent;
    transition: transform 160ms var(--brand-ease);
  }

  .with-parent-column .brand-lockup {
    gap: 0;
    margin-left: calc(
      var(--brand-align) - var(--brand-pad) - var(--brand-chevron)
    );
  }

  /* The highlight is one surface behind the whole lockup. Without a parent it
     starts at the mark, with a parent it grows left to take in the chevron. */
  .brand-lockup::before {
    position: absolute;
    z-index: -1;
    inset: 2px 0 2px 0;
    border-radius: inherit;
    background: var(--surface-raised);
    opacity: 0;
    content: "";
    transition:
      opacity 160ms ease,
      left 280ms var(--brand-ease);
  }

  .with-parent-column .brand-lockup:not(.parent-link)::before {
    left: var(--brand-chevron);
  }

  .brand-lockup:hover::before,
  .brand-lockup:focus-visible::before {
    opacity: 1;
  }

  .brand-lockup:active {
    transform: scale(0.97);
  }

  .brand-lockup:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: 2px;
  }

  .brand-chevron {
    display: grid;
    width: var(--brand-chevron);
    flex: 0 0 var(--brand-chevron);
    place-items: center;
    color: var(--ink-muted);
    opacity: 0;
    /* Hidden state: tucked behind the mark, ready to slide out. */
    transform: translateX(0.55rem) scale(0.6);
    transition:
      opacity 180ms ease,
      color 160ms ease,
      transform 280ms var(--brand-ease);
  }

  .brand-chevron :global(svg) {
    width: 1.15rem;
    height: 1.15rem;
    flex: 0 0 auto;
    stroke-width: 2.2;
  }

  .parent-link .brand-chevron {
    opacity: 1;
    transform: none;
  }

  .parent-link:hover .brand-chevron,
  .parent-link:focus-visible .brand-chevron {
    color: var(--accent-strong);
    transform: translateX(-2px);
  }

  .brand-mark-wrap {
    display: block;
    flex: 0 0 auto;
    transition: transform 420ms var(--brand-ease);
  }

  .with-parent-column .brand-mark-wrap {
    margin-right: 0.6rem;
  }

  /* The yarn ball rolls a little towards where the link goes. */
  .brand-lockup:hover .brand-mark-wrap {
    transform: rotate(14deg);
  }

  .parent-link:hover .brand-mark-wrap {
    transform: rotate(-14deg);
  }

  .brand-copy {
    display: flex;
    min-width: 0;
    flex-direction: column;
    justify-content: center;
    line-height: 1.15;
  }

  .brand-title {
    font-size: 1rem;
    font-weight: 680;
    letter-spacing: -0.025em;
  }

  .brand-version {
    margin-top: 0.12rem;
    color: var(--ink-muted);
    font-size: 0.7rem;
    font-weight: 450;
    letter-spacing: 0.01em;
  }

  @media (max-width: 760px) {
    /* No rail to line up with: the chevron uses the header's side padding. */
    .brand-header {
      --brand-align: 0.25rem;
    }
  }

  @media (max-width: 390px) {
    .brand-header {
      --brand-align: 0.5rem;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .brand-lockup,
    .brand-lockup::before,
    .brand-chevron,
    .brand-mark-wrap {
      transition: none;
    }

    .brand-lockup:active,
    .brand-lockup:hover .brand-mark-wrap,
    .parent-link:hover .brand-chevron,
    .parent-link:focus-visible .brand-chevron {
      transform: none;
    }
  }
</style>
