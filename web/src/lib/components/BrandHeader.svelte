<script lang="ts">
  import BrandMark from "#lib/components/BrandMark.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { appVersion } from "#lib/app-version.ts";
  import {
    ease,
    handoff,
    morphDistance,
    morphTransform,
    scrollProgress,
    slotProgress,
    type Rect,
  } from "#lib/components/brand-handoff.svelte.ts";

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

  // On Home the greeting mascot is the hero. While it is on screen the logo
  // slot is collapsed (the wordmark sits flush left). Scrolling morphs the
  // hero into the slot: a fixed clone of the mascot shrinks and travels to the
  // slot while the slot grows and pushes the wordmark right. Only one mascot
  // is visible at a time: the hero hides while the clone is out, the clone
  // hides once the real mark is shown. With reduced motion there is no
  // morph: the mark just fades in once the hero has scrolled away.
  let headerEl: HTMLElement | undefined = $state();
  let markEl: HTMLElement | undefined = $state();
  let markShown = $state(true);

  $effect(() => {
    const hero = handoff.hero;
    const header = headerEl;
    const slot = markEl;
    if (!hero || !header || !slot) {
      markShown = true;
      return;
    }
    const reducedQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    let clone: HTMLElement | null = null;
    let frame = 0;
    let lastProgress = -1;

    function ensureClone(): HTMLElement | null {
      const art = hero?.querySelector("svg");
      if (!art) return null;
      clone?.remove();
      const svg = art.cloneNode(true) as SVGElement;
      svg.removeAttribute("width");
      svg.removeAttribute("height");
      svg.style.cssText = "display:block;width:100%;height:100%";
      const el = document.createElement("div");
      el.append(svg);
      el.setAttribute("aria-hidden", "true");
      el.style.cssText =
        "position:fixed;z-index:60;left:0;top:0;pointer-events:none;" +
        "transform-origin:0 0;will-change:transform;display:none";
      document.body.append(el);
      return el;
    }

    function update() {
      frame = 0;
      if (!hero || !header || !slot) return;
      const reduced = reducedQuery.matches;
      const slotBox = slot.getBoundingClientRect();
      const heroBox = hero.getBoundingClientRect();
      const scrollY = window.scrollY;
      const distance = morphDistance(heroBox.top + scrollY, slotBox.top);
      const progress = scrollProgress(scrollY, distance);
      const eased = reduced ? slotProgress(progress, true) : ease(progress);
      header.style.setProperty("--brand-p", String(eased));
      markShown = eased >= 1;

      // The clone is only out while the morph is under way.
      const morphing = !reduced && progress > 0 && progress < 1;
      hero.style.visibility = !reduced && progress > 0 ? "hidden" : "";
      if (!morphing) {
        if (clone) clone.style.display = "none";
        lastProgress = progress;
        return;
      }
      if (!clone || lastProgress <= 0 || lastProgress >= 1) {
        clone = ensureClone();
      }
      lastProgress = progress;
      if (!clone) {
        hero.style.visibility = "";
        return;
      }
      const from: Rect = {
        left: heroBox.left,
        top: heroBox.top,
        size: heroBox.width,
      };
      const to: Rect = {
        left: slotBox.left,
        top: slotBox.top,
        size: slotBox.height,
      };
      const t = morphTransform(from, to, eased, from.size);
      clone.style.width = `${from.size}px`;
      clone.style.height = `${from.size}px`;
      clone.style.transform = `translate(${t.x}px, ${t.y}px) scale(${t.scale})`;
      clone.style.display = "block";
    }

    function schedule() {
      if (!frame) frame = requestAnimationFrame(update);
    }

    update();
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule, { passive: true });
    reducedQuery.addEventListener("change", schedule);
    return () => {
      window.removeEventListener("scroll", schedule);
      window.removeEventListener("resize", schedule);
      reducedQuery.removeEventListener("change", schedule);
      if (frame) cancelAnimationFrame(frame);
      clone?.remove();
      hero.style.visibility = "";
      header.style.removeProperty("--brand-p");
      markShown = true;
    };
  });
</script>

<div
  class="brand-header"
  class:with-parent-column={reserveParentSlot}
  bind:this={headerEl}
>
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
    <span
      class="brand-mark-wrap"
      class:mark-hidden={!markShown}
      bind:this={markEl}><BrandMark /></span
    >
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
    --brand-pad: 0.25rem;
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
    margin-left: calc(var(--brand-align) - var(--brand-pad));
    border-radius: var(--radius-sm);
    padding: 0 0.65rem 0 var(--brand-pad);
    color: var(--ink);
    text-decoration: none;
    -webkit-tap-highlight-color: transparent;
    transition: transform 160ms var(--brand-ease);
  }

  .with-parent-column .brand-lockup {
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

  /* The slot grows with --brand-p (0 collapsed, 1 full; unset means full) and
     pushes the wordmark with it. */
  .brand-mark-wrap {
    display: block;
    width: calc(2rem * var(--brand-p, 1));
    flex: 0 0 auto;
    margin-right: calc(0.6rem * var(--brand-p, 1));
    transition: transform 420ms var(--brand-ease);
  }

  /* While the hero is the mascot (or on its way into the slot) the mark
     itself stays invisible. */
  .brand-mark-wrap.mark-hidden {
    opacity: 0;
    pointer-events: none;
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
    font-family: var(--font-display);
    font-size: 1.3rem;
    font-weight: 700;
    letter-spacing: -0.01em;
    font-variation-settings:
      "SOFT" 100,
      "WONK" 1;
  }

  .brand-version {
    margin-top: 0.12rem;
    color: var(--ink-muted);
    font-size: 0.7rem;
    font-weight: 600;
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
    .brand-chevron {
      transition: none;
    }

    /* No morph: the slot opens and the mark fades in once. */
    .brand-mark-wrap {
      transition:
        opacity 160ms ease,
        width 160ms ease,
        margin-right 160ms ease;
    }

    .brand-lockup:active,
    .brand-lockup:hover .brand-mark-wrap,
    .parent-link:hover .brand-chevron,
    .parent-link:focus-visible .brand-chevron {
      transform: none;
    }
  }
</style>
