<script lang="ts">
  import { tick, type Snippet } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import {
    CARD_TILT,
    FAB_TILT,
    MORPH_EASING,
    MORPH_MS,
    SWAY_KEYFRAMES,
    SWAY_MS,
    SWAY_SETTLE_MS,
    createNotesDisclosure,
    flipFrom,
    type NotesDirection,
    type NotesPhase,
  } from "./notes-disclosure";

  // The Notes section as a paper sticky note. With content it rests as a small
  // FAB-sized note at the right edge of the screen and morphs into the full
  // card when tapped (and back); on paper it prints inline. With `emptyHref`
  // and no content it is the faint dashed "Add a note" invitation instead.
  let {
    title,
    headingId,
    headingLevel = 2,
    closeLabel = "Close notes",
    emptyHref = "",
    emptyLabel = "",
    children,
  }: {
    title: string;
    headingId: string;
    headingLevel?: 2 | 3;
    closeLabel?: string;
    emptyHref?: string;
    emptyLabel?: string;
    children?: Snippet;
  } = $props();

  const cardId = $derived(`${headingId}-panel`);
  let phase = $state<NotesPhase>("closed");
  const expanded = $derived(phase === "open" || phase === "opening");

  let fab = $state<HTMLButtonElement>();
  let fabPaper = $state<HTMLElement>();
  let card = $state<HTMLElement>();
  let paper = $state<HTMLElement>();
  let sheet = $state<HTMLElement>();
  let fold = $state<HTMLElement>();
  let pinHead = $state<HTMLElement>();
  let body = $state<HTMLElement>();
  let scrim = $state<HTMLElement>();

  // The running morph, kept so that closing (or a second tap mid-way) plays
  // the very same animations backwards along their own path.
  let morph: Animation[] | null = null;
  let settle: ((finished: boolean) => void) | null = null;
  let sway: Animation | null = null;

  const reducedMotion = () =>
    typeof matchMedia === "function" &&
    matchMedia("(prefers-reduced-motion: reduce)").matches;

  function buildMorph(): Animation[] {
    if (!fab || !card) return [];
    if (reducedMotion()) {
      // Crossfade only: no sway, morph or tilt.
      const fade = { duration: 160, easing: "ease-out", fill: "both" } as const;
      return [
        card.animate([{ opacity: 0 }, { opacity: 1 }], fade),
        fab.animate([{ opacity: 1 }, { opacity: 0 }], fade),
        ...(scrim
          ? [scrim.animate([{ opacity: 0 }, { opacity: 1 }], fade)]
          : []),
      ];
    }
    const fabRect = fab.getBoundingClientRect();
    const cardRect = card.getBoundingClientRect();
    const { dx, dy, sx, sy } = flipFrom(fabRect, cardRect, fabRect.height);
    const timing = {
      duration: MORPH_MS,
      easing: MORPH_EASING,
      fill: "both",
    } as const;
    // Parts of the card, all with transform-origin at the pin (top centre).
    const foldSize = fold?.offsetWidth ?? 31;
    const foldK = Math.min(1, (fabRect.width * 0.21) / foldSize);
    const fx = (-cardRect.width * (1 - sx)) / 2;
    const fy = -cardRect.height * (1 - sy);
    const anims: Animation[] = [
      card.animate(
        [
          { transform: `translate(${dx}px, ${dy}px)` },
          { transform: "translate(0px, 0px)" },
        ],
        timing,
      ),
    ];
    if (paper) {
      anims.push(
        paper.animate(
          [
            { transform: `rotate(${FAB_TILT}deg)` },
            { transform: `rotate(${CARD_TILT}deg)` },
          ],
          timing,
        ),
      );
    }
    if (sheet) {
      anims.push(
        sheet.animate(
          [
            { transform: `scale(${sx}, ${sy})`, opacity: 0, offset: 0 },
            { opacity: 1, offset: 0.35 },
            { transform: "scale(1, 1)", opacity: 1, offset: 1 },
          ],
          timing,
        ),
      );
    }
    if (fold) {
      anims.push(
        fold.animate(
          [
            {
              transform: `translate(${fx}px, ${fy}px) scale(${foldK})`,
              opacity: 0,
              offset: 0,
            },
            { opacity: 1, offset: 0.35 },
            {
              transform: "translate(0px, 0px) scale(1)",
              opacity: 1,
              offset: 1,
            },
          ],
          timing,
        ),
      );
    }
    if (pinHead) {
      anims.push(
        pinHead.animate(
          [{ transform: "scale(0.63)" }, { transform: "scale(1)" }],
          timing,
        ),
      );
    }
    if (body) {
      anims.push(
        body.animate(
          [
            { opacity: 0, transform: "translateY(10px)" },
            { opacity: 1, transform: "translateY(0px)" },
          ],
          {
            delay: 120,
            duration: 200,
            easing: "cubic-bezier(0.2, 0.7, 0.2, 1)",
            fill: "both",
          },
        ),
      );
    }
    if (scrim) {
      anims.push(scrim.animate([{ opacity: 0 }, { opacity: 1 }], timing));
    }
    anims.push(
      fab.animate(
        [
          { opacity: 1, offset: 0 },
          { opacity: 0, offset: 0.35 },
          { opacity: 0, offset: 1 },
        ],
        timing,
      ),
    );
    return anims;
  }

  async function run(direction: NotesDirection): Promise<boolean> {
    settle?.(false);
    return new Promise<boolean>((resolve) => {
      settle = resolve;
      void (async () => {
        sway?.cancel();
        if (direction === "open" && !morph) {
          await tick(); // the card is display: block from here on
          if (settle !== resolve) return;
          morph = buildMorph();
        } else if (morph) {
          for (const animation of morph) animation.reverse();
        }
        const lead = morph?.[0];
        if (!lead) {
          settle = null;
          resolve(true);
          return;
        }
        lead.onfinish = () => {
          if (settle !== resolve) return;
          settle = null;
          resolve(true);
        };
      })();
    });
  }

  function focusCard() {
    void tick().then(() => card?.focus({ preventScroll: true }));
  }

  function focusIsHome() {
    const active = document.activeElement;
    return !active || active === document.body || !!card?.contains(active);
  }

  const disclosure = createNotesDisclosure({
    run,
    onPhase(next) {
      phase = next;
      if (next === "closed" && morph) {
        for (const animation of morph) animation.cancel();
        morph = null;
      }
    },
    focusCard,
    focusFab: () => fab?.focus({ preventScroll: true }),
    shouldRestoreFocus: focusIsHome,
  });

  function swayOnce() {
    if (!fabPaper || disclosure.phase !== "closed" || reducedMotion()) return;
    sway?.cancel();
    sway = fabPaper.animate(
      SWAY_KEYFRAMES.map(({ offset, delta }) => ({
        offset,
        transform: `rotate(${FAB_TILT + delta}deg)`,
        easing: "ease-in-out",
      })),
      { duration: SWAY_MS, fill: "none" },
    );
  }

  $effect(() => {
    if (emptyHref) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    // A gentle sway once scrolling has stopped. Capture, so that any
    // scrolling container counts.
    const onScroll = () => {
      if (disclosure.phase !== "closed") return;
      clearTimeout(timer);
      timer = setTimeout(swayOnce, SWAY_SETTLE_MS);
    };
    const onKey = (event: KeyboardEvent) => {
      if (disclosure.handleKey(event.key)) event.preventDefault();
    };
    const onPointerDown = (event: PointerEvent) => {
      if (disclosure.phase !== "open" && disclosure.phase !== "opening") return;
      const target = event.target as Node | null;
      if (target && (card?.contains(target) || fab?.contains(target))) return;
      disclosure.close();
    };
    document.addEventListener("scroll", onScroll, {
      capture: true,
      passive: true,
    });
    document.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("scroll", onScroll, true);
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onPointerDown, true);
      sway?.cancel();
      for (const animation of morph ?? []) animation.cancel();
      morph = null;
    };
  });
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
  <div class="notes" data-phase={phase}>
    <button
      bind:this={fab}
      type="button"
      class="notes__fab"
      aria-expanded={expanded}
      aria-controls={cardId}
      aria-label={title}
      onclick={() => disclosure.toggle()}
    >
      <span bind:this={fabPaper} class="notes__fab-paper" aria-hidden="true">
        <svg viewBox="0 0 24 24" class="notes__glyph" focusable="false">
          <path d="M6 9h12M6 13h12M6 17h7" />
        </svg>
      </span>
    </button>
    <div bind:this={scrim} class="notes__scrim" aria-hidden="true"></div>
    <section
      bind:this={card}
      id={cardId}
      class="notes__card"
      aria-labelledby={headingId}
      tabindex="-1"
    >
      <div bind:this={paper} class="notes__paper" aria-hidden="true">
        <div bind:this={sheet} class="notes__sheet"></div>
        <div bind:this={fold} class="notes__fold"></div>
      </div>
      <button
        type="button"
        class="notes__pin"
        aria-label={closeLabel}
        onclick={() => disclosure.close()}
      >
        <span bind:this={pinHead} class="notes__pin-head"></span>
      </button>
      <div bind:this={body} class="notes__body">
        <div class="notes__scroll">
          <svelte:element
            this={`h${headingLevel}`}
            id={headingId}
            class="sticky__title">{title}</svelte:element
          >
          {@render children?.()}
        </div>
        <button
          type="button"
          class="notes__close"
          aria-label={closeLabel}
          onclick={() => disclosure.close()}
        >
          <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
            <path d="M7 7l10 10M17 7L7 17" />
          </svg>
        </button>
      </div>
    </section>
  </div>
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
  /* ---- Notes as a floating note ------------------------------------- */

  .notes {
    --fold: 1.4rem;
    --notes-right: max(1.25rem, env(safe-area-inset-right));
    --notes-bottom: clamp(4.5rem, 24dvh, 14rem);
    --notes-ease: cubic-bezier(0.3, 1.3, 0.5, 1);
    --sticky-shadows: drop-shadow(0 1px 1px rgb(var(--sticky-shadow) / 22%))
      drop-shadow(0 6px 8px rgb(var(--sticky-shadow) / 16%))
      drop-shadow(0 14px 18px rgb(var(--sticky-shadow) / 10%));
    color: var(--sticky-ink);
  }

  .notes__fab {
    position: fixed;
    z-index: 6;
    right: var(--notes-right);
    bottom: var(--notes-bottom);
    width: 3.5rem;
    height: 3.5rem;
    margin: 0;
    border: 0;
    padding: 0;
    color: var(--sticky-ink);
    background: none;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }

  .notes[data-phase="open"] .notes__fab {
    visibility: hidden;
  }

  .notes__fab:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 5px;
    border-radius: 12px;
  }

  /* The mini note: tilted, with its pin and folded corner. The sway rotates
     it around the pin, so the origin sits at the pin head. */
  .notes__fab-paper {
    --fold: 0.75rem;
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    border-radius: 9px 9px 3px 9px;
    background: linear-gradient(
      315deg,
      transparent var(--fold),
      var(--sticky-paper) var(--fold)
    );
    filter: var(--sticky-shadows);
    transform: rotate(4deg);
    transform-origin: 50% 0.35rem;
  }

  .notes__fab-paper::before {
    content: "";
    position: absolute;
    top: -0.3rem;
    left: 50%;
    width: 0.85rem;
    height: 0.85rem;
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
      2px 3px 4px rgb(var(--sticky-shadow) / 30%);
    transform: translateX(-50%);
  }

  .notes__fab-paper::after {
    content: "";
    position: absolute;
    right: 0;
    bottom: 0;
    width: calc(var(--fold) * 1.4142);
    height: calc(var(--fold) * 1.4142);
    border-top-left-radius: 2px;
    background: linear-gradient(
      315deg,
      transparent 50%,
      var(--sticky-fold) 50%
    );
  }

  .notes__glyph {
    width: 1.5rem;
    height: 1.5rem;
    margin-top: 0.2rem;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    opacity: 0.7;
  }

  .notes__scrim {
    display: none;
  }

  /* The card. Final geometry comes from CSS; the morph measures it. */
  .notes__card {
    position: fixed;
    z-index: 7;
    right: var(--notes-right);
    bottom: var(--notes-bottom);
    display: none;
    box-sizing: border-box;
    width: min(22.5rem, calc(100vw - 2.5rem));
    color: var(--sticky-ink);
  }

  .notes[data-phase="opening"] .notes__card,
  .notes[data-phase="open"] .notes__card,
  .notes[data-phase="closing"] .notes__card {
    display: block;
  }

  .notes__card:focus {
    outline: none;
  }

  .notes__card:focus-visible {
    outline: 2px solid var(--focus);
    outline-offset: 6px;
    border-radius: 14px;
  }

  .notes__paper {
    position: absolute;
    inset: 0;
    transform: rotate(-1.5deg);
    transform-origin: 50% 0;
    pointer-events: none;
  }

  .notes__sheet {
    position: absolute;
    inset: 0;
    border-radius: 14px 14px 4px 14px;
    background: linear-gradient(
      315deg,
      transparent var(--fold),
      var(--sticky-paper) var(--fold)
    );
    filter: var(--sticky-shadows);
    transform-origin: 50% 0;
  }

  .notes__fold {
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
    transform-origin: 100% 100%;
  }

  /* The pin doubles as the close button: a 44px target around the head. */
  .notes__pin {
    position: absolute;
    z-index: 2;
    top: -1.25rem;
    left: 50%;
    display: grid;
    width: 2.75rem;
    height: 2.75rem;
    margin: 0 0 0 -1.375rem;
    border: 0;
    border-radius: 50%;
    padding: 0;
    place-items: center;
    background: none;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }

  .notes__pin-head {
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
  }

  .notes__pin:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 0;
  }

  .notes__body {
    position: relative;
  }

  .notes__scroll {
    box-sizing: border-box;
    max-height: 70dvh;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding: 1.5rem 2.6rem 1.7rem 1.4rem;
  }

  .notes__scroll :global(a) {
    color: var(--sticky-link);
  }

  .notes__scroll :global(.rich-text) {
    color: var(--sticky-ink);
  }

  .notes__scroll :global(img) {
    max-width: 100%;
    height: auto;
  }

  .notes__close {
    position: absolute;
    top: 0.45rem;
    right: 0.5rem;
    display: grid;
    width: 2.75rem;
    height: 2.75rem;
    margin: 0;
    border: 0;
    border-radius: 50%;
    padding: 0;
    place-items: center;
    color: var(--sticky-ink);
    background: none;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }

  .notes__close svg {
    width: 1.1rem;
    height: 1.1rem;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    opacity: 0.55;
  }

  .notes__close:hover svg,
  .notes__close:focus-visible svg {
    opacity: 1;
  }

  .notes__close:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: -2px;
  }

  @media (hover: hover) and (prefers-reduced-motion: no-preference) {
    .notes__fab {
      transition: translate 160ms ease;
    }

    .notes__fab:hover {
      translate: -2px -2px;
    }
  }

  @media (max-width: 760px) {
    .notes {
      --notes-right: max(0.6rem, env(safe-area-inset-right));
      --notes-bottom: calc(
        var(--app-mobile-nav-height, 57px) +
          var(
            --app-mobile-nav-bottom-inset,
            max(4px, env(safe-area-inset-bottom, 0px))
          ) +
          1rem
      );
    }

    .notes__card {
      right: 0;
      left: 0;
      width: 92vw;
      margin-inline: auto;
    }

    .notes__scroll {
      max-height: min(
        70dvh,
        calc(100dvh - var(--notes-bottom) - 5.5rem - env(safe-area-inset-top))
      );
    }

    /* Light scrim behind the card on phones only. */
    .notes__scrim {
      position: fixed;
      z-index: 6;
      inset: 0;
      background: rgb(var(--sticky-shadow) / 28%);
    }

    .notes[data-phase="opening"] .notes__scrim,
    .notes[data-phase="open"] .notes__scrim,
    .notes[data-phase="closing"] .notes__scrim {
      display: block;
    }
  }

  @media print {
    .notes__card {
      position: static;
      display: block !important;
      width: auto;
      margin: 1rem 0;
      color: #000;
    }

    .notes__fab,
    .notes__scrim,
    .notes__paper,
    .notes__pin,
    .notes__close {
      display: none !important;
    }

    .notes__scroll {
      max-height: none;
      overflow: visible;
      padding: 0;
    }

    .notes__scroll :global(.rich-text),
    .notes__scroll :global(a) {
      color: #000;
    }
  }
</style>
