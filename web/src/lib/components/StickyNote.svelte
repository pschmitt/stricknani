<script lang="ts">
  import { tick, type Snippet } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import {
    CARD_TILT,
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
  import {
    SNAP_EASING,
    SNAP_MS,
    SNAP_REDUCED_MS,
    clampToViewport,
    createFabDrag,
    createTiltTracker,
    defaultPlacement,
    describeMove,
    fitCardBottom,
    isMoveKey,
    loadPlacement,
    placementToPoint,
    restTilt,
    savePlacement,
    snapPlacement,
    stepPlacement,
    type FabGeometry,
    type MoveAnnouncement,
    type Placement,
    type Side,
  } from "./notes-drag";

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
    moveHint = "Long-press and drag to move",
    movedLabels = {
      left: "Notes moved to the left",
      right: "Notes moved to the right",
      up: "Notes moved up",
      down: "Notes moved down",
    },
    children,
  }: {
    title: string;
    headingId: string;
    headingLevel?: 2 | 3;
    closeLabel?: string;
    emptyHref?: string;
    emptyLabel?: string;
    moveHint?: string;
    movedLabels?: Record<MoveAnnouncement, string>;
    children?: Snippet;
  } = $props();

  const cardId = $derived(`${headingId}-panel`);
  let phase = $state<NotesPhase>("closed");
  const expanded = $derived(phase === "open" || phase === "opening");

  let root = $state<HTMLElement>();
  let probe = $state<HTMLElement>();
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

  // Where the FAB rests and how it is being moved. The FAB is placed with a
  // transform from the top-left corner once mounted; until then CSS puts it
  // at the default spot on the right edge.
  let side = $state<Side>("right");
  let placed = $state(false);
  let lifted = $state(false);
  let dragging = $state(false);
  let announcement = $state("");
  let placement: Placement | null = null; // null: the CSS default spot
  let current = { x: 0, y: 0 };
  let fabAnim: Animation | null = null;
  let announceTimer: ReturnType<typeof setTimeout> | undefined;

  const drag = createFabDrag();
  let tracker = createTiltTracker();
  let longPressTimer: ReturnType<typeof setTimeout> | undefined;
  let suppressClick = false;
  let suppressTimer: ReturnType<typeof setTimeout> | undefined;
  let frameId = 0;
  let pointerX = 0;
  let startPoint = { x: 0, y: 0 };
  let dragGeometry: FabGeometry | null = null;

  const probeRect = (name: string) =>
    probe
      ?.querySelector<HTMLElement>(`[data-probe="${name}"]`)
      ?.getBoundingClientRect();

  function measure(): (FabGeometry & { defaultTop: number }) | null {
    const area = probe?.getBoundingClientRect();
    const top = probeRect("top");
    const bottom = probeRect("bottom");
    const left = probeRect("left");
    const right = probeRect("right");
    const rest = probeRect("default");
    if (!area || !fab || !top || !bottom || !left || !right || !rest) {
      return null;
    }
    return {
      width: area.width,
      height: area.height,
      size: fab.offsetWidth,
      top: top.top,
      bottomLimit: area.bottom - bottom.bottom,
      insetLeft: left.left - area.left,
      insetRight: area.right - right.right,
      defaultTop: rest.top,
    };
  }

  function setPoint(point: { x: number; y: number }) {
    current = point;
    if (fab) {
      fab.style.transform = `translate3d(${point.x}px, ${point.y}px, 0)`;
    }
  }

  /** Puts the FAB on its stored (or default) spot, without animating. */
  function layout() {
    const g = measure();
    if (!g) return;
    const rest = placement ?? defaultPlacement(g.defaultTop, g);
    side = rest.side;
    setPoint(placementToPoint(rest, g));
    placed = true;
  }

  function springTo(point: { x: number; y: number }, wobble: boolean) {
    if (!fab) return;
    const from = current;
    fabAnim?.cancel();
    fabAnim = null;
    setPoint(point);
    if (from.x === point.x && from.y === point.y) {
      if (wobble) swayOnce();
      return;
    }
    const reduced = reducedMotion();
    const frame = (p: { x: number; y: number }) =>
      `translate3d(${p.x}px, ${p.y}px, 0)`;
    const anim = fab.animate(
      [{ transform: frame(from) }, { transform: frame(point) }],
      {
        duration: reduced ? SNAP_REDUCED_MS : SNAP_MS,
        easing: reduced ? "linear" : SNAP_EASING,
      },
    );
    fabAnim = anim;
    anim.onfinish = () => {
      if (fabAnim !== anim) return;
      fabAnim = null;
      fabPaper?.style.removeProperty("rotate");
      if (wobble) swayOnce();
    };
  }

  function announce(text: string) {
    clearTimeout(announceTimer);
    announcement = "";
    // A fresh text node each time, so repeated moves are read out again.
    announceTimer = setTimeout(() => (announcement = text), 60);
  }

  function pickUp() {
    const g = measure();
    if (!g || !fab) return;
    dragGeometry = g;
    startPoint = current;
    lifted = true;
    dragging = true;
    sway?.cancel();
    tracker = createTiltTracker();
    frameId = requestAnimationFrame(dragFrame);
  }

  function dragFrame(now: number) {
    if (!drag.dragging) return;
    if (fabPaper && !reducedMotion()) {
      fabPaper.style.rotate = `${tracker.frame(pointerX, now)}deg`;
    }
    frameId = requestAnimationFrame(dragFrame);
  }

  function release(event: PointerEvent) {
    try {
      fab?.releasePointerCapture(event.pointerId);
    } catch {
      // Capture was already gone.
    }
  }

  function endDrag() {
    cancelAnimationFrame(frameId);
    lifted = false;
    dragging = false;
    const g = dragGeometry ?? measure();
    dragGeometry = null;
    if (!g) return;
    const next = snapPlacement(current, g);
    placement = next;
    side = next.side;
    savePlacement(next);
    if (fabPaper) fabPaper.style.rotate = "0deg";
    springTo(placementToPoint(next, g), true);
  }

  function onPointerDown(event: PointerEvent) {
    if (disclosure.phase !== "closed" || !placed) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (fabAnim && fab) {
      const rect = fab.getBoundingClientRect();
      fabAnim.cancel();
      fabAnim = null;
      setPoint({ x: rect.left, y: rect.top });
    }
    startPoint = current;
    pointerX = event.clientX;
    const kind =
      event.pointerType === "touch"
        ? "touch"
        : event.pointerType === "pen"
          ? "pen"
          : "mouse";
    const delay = drag.down(kind, event.clientX, event.clientY);
    try {
      fab?.setPointerCapture(event.pointerId);
    } catch {
      // Synthetic events have no capturable pointer.
    }
    clearTimeout(longPressTimer);
    if (delay !== null) {
      longPressTimer = setTimeout(() => {
        if (drag.longPress()) pickUp();
      }, delay);
    }
  }

  function onPointerMove(event: PointerEvent) {
    const result = drag.move(event.clientX, event.clientY);
    pointerX = event.clientX;
    if (result === "cancel") {
      clearTimeout(longPressTimer);
      release(event);
      return;
    }
    if (result === "pickup") pickUp();
    if (!drag.dragging || !dragGeometry) return;
    event.preventDefault();
    const { dx, dy } = drag.offset;
    setPoint(
      clampToViewport(
        { x: startPoint.x + dx, y: startPoint.y + dy },
        dragGeometry,
      ),
    );
  }

  function onPointerUp(event: PointerEvent) {
    clearTimeout(longPressTimer);
    const outcome = drag.up();
    release(event);
    if (outcome !== "drop") return;
    suppressClick = true;
    clearTimeout(suppressTimer);
    suppressTimer = setTimeout(() => (suppressClick = false), 120);
    endDrag();
  }

  function onPointerCancel(event: PointerEvent) {
    clearTimeout(longPressTimer);
    release(event);
    if (drag.cancel()) endDrag();
  }

  function onFabClick(event: MouseEvent) {
    if (suppressClick) {
      suppressClick = false;
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    disclosure.toggle();
  }

  function onFabKeydown(event: KeyboardEvent) {
    if (
      !event.altKey ||
      event.ctrlKey ||
      event.metaKey ||
      event.shiftKey ||
      !isMoveKey(event.key)
    ) {
      return;
    }
    event.preventDefault();
    if (disclosure.phase !== "closed" || drag.phase !== "idle") return;
    const g = measure();
    if (!g) return;
    const from = placement ?? defaultPlacement(g.defaultTop, g);
    const next = stepPlacement(from, event.key);
    const moved = describeMove(from, next);
    if (!moved) return;
    placement = next;
    side = next.side;
    savePlacement(next);
    springTo(placementToPoint(next, g), true);
    announce(movedLabels[moved]);
  }

  /** Keeps the open card next to the FAB but inside the visible band. */
  function fitCard() {
    const g = measure();
    if (!g || !fab || !card || !root) return;
    const fabBottom = g.height - fab.getBoundingClientRect().bottom;
    const bottom = fitCardBottom(fabBottom, card.offsetHeight, g);
    root.style.setProperty("--notes-card-bottom", `${bottom}px`);
  }

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
            { transform: `rotate(${restTilt(side)}deg)` },
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
          fitCard();
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
    if (
      !fabPaper ||
      disclosure.phase !== "closed" ||
      drag.dragging ||
      reducedMotion()
    ) {
      return;
    }
    sway?.cancel();
    const rest = restTilt(side);
    const mirror = side === "left" ? -1 : 1;
    sway = fabPaper.animate(
      SWAY_KEYFRAMES.map(({ offset, delta }) => ({
        offset,
        transform: `rotate(${rest + delta * mirror}deg)`,
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
    // Not in the Svelte attribute typings yet; the title says the same.
    fab?.setAttribute("aria-description", moveHint);
    // Stored spot first, so the FAB is where the person left it.
    placement = loadPlacement();
    layout();
    const onResize = () => {
      if (disclosure.phase === "closed" && drag.phase === "idle") {
        fabAnim?.cancel();
        fabAnim = null;
        layout();
      }
    };
    const onTouchMove = (event: TouchEvent) => {
      if (drag.dragging) event.preventDefault();
    };
    const onContextMenu = (event: Event) => {
      if (drag.phase !== "idle") event.preventDefault();
    };
    window.addEventListener("resize", onResize);
    window.addEventListener("orientationchange", onResize);
    fab?.addEventListener("touchmove", onTouchMove, { passive: false });
    fab?.addEventListener("contextmenu", onContextMenu);
    document.addEventListener("scroll", onScroll, {
      capture: true,
      passive: true,
    });
    document.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => {
      clearTimeout(timer);
      clearTimeout(longPressTimer);
      clearTimeout(suppressTimer);
      clearTimeout(announceTimer);
      cancelAnimationFrame(frameId);
      fabAnim?.cancel();
      fabAnim = null;
      window.removeEventListener("resize", onResize);
      window.removeEventListener("orientationchange", onResize);
      fab?.removeEventListener("touchmove", onTouchMove);
      fab?.removeEventListener("contextmenu", onContextMenu);
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
  <div
    bind:this={root}
    class="notes"
    class:notes--placed={placed}
    data-phase={phase}
    data-side={side}
    data-lift={lifted ? "" : undefined}
  >
    <div bind:this={probe} class="notes__probe" aria-hidden="true">
      <span data-probe="top"></span>
      <span data-probe="bottom"></span>
      <span data-probe="left"></span>
      <span data-probe="right"></span>
      <span data-probe="default"></span>
    </div>
    <span class="notes__live" role="status" aria-live="polite"
      >{announcement}</span
    >
    <button
      bind:this={fab}
      type="button"
      class="notes__fab"
      class:notes__fab--dragging={dragging}
      aria-expanded={expanded}
      aria-controls={cardId}
      aria-label={title}
      title={moveHint}
      onclick={onFabClick}
      onkeydown={onFabKeydown}
      onpointerdown={onPointerDown}
      onpointermove={onPointerMove}
      onpointerup={onPointerUp}
      onpointercancel={onPointerCancel}
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
    --notes-left: max(1.25rem, env(safe-area-inset-left));
    --notes-bottom: clamp(4.5rem, 24dvh, 14rem);
    --notes-bottom-limit: calc(env(safe-area-inset-bottom, 0px) + 1rem);
    --fab-tilt: 4deg;
    --notes-ease: cubic-bezier(0.3, 1.3, 0.5, 1);
    --sticky-shadows: drop-shadow(0 1px 1px rgb(var(--sticky-shadow) / 22%))
      drop-shadow(0 6px 8px rgb(var(--sticky-shadow) / 16%))
      drop-shadow(0 14px 18px rgb(var(--sticky-shadow) / 10%));
    /* The same stack lifted: every offset grows by 4px. */
    --sticky-shadows-lift: drop-shadow(
        0 5px 1px rgb(var(--sticky-shadow) / 22%)
      )
      drop-shadow(0 10px 8px rgb(var(--sticky-shadow) / 18%))
      drop-shadow(0 18px 18px rgb(var(--sticky-shadow) / 12%));
    color: var(--sticky-ink);
  }

  .notes[data-side="left"] {
    --fab-tilt: -4deg;
  }

  /* Measuring stick for the drag geometry: the viewport, with a marker at
     each limit the FAB has to respect. Never painted. */
  .notes__probe {
    position: fixed;
    inset: 0;
    visibility: hidden;
    pointer-events: none;
  }

  .notes__probe span {
    position: absolute;
    width: 0;
    height: 0;
  }

  .notes__probe [data-probe="top"] {
    top: calc(var(--app-header-height, 4.25rem) + 1rem);
  }

  .notes__probe [data-probe="bottom"] {
    bottom: var(--notes-bottom-limit);
  }

  .notes__probe [data-probe="left"] {
    left: var(--notes-left);
  }

  .notes__probe [data-probe="right"] {
    right: var(--notes-right);
  }

  .notes__probe [data-probe="default"] {
    bottom: var(--notes-bottom);
    height: 3.5rem;
  }

  .notes__live {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
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
    -webkit-touch-callout: none;
    -webkit-user-select: none;
    user-select: none;
  }

  /* Once placed from script the FAB sits at the top-left corner and a
     transform moves it, so dragging never touches layout. */
  .notes--placed .notes__fab {
    top: 0;
    right: auto;
    bottom: auto;
    left: 0;
  }

  /* No page scroll while it is being moved. */
  .notes__fab--dragging {
    touch-action: none;
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
    transform: rotate(var(--fab-tilt));
    transform-origin: 50% 0.35rem;
  }

  @media (prefers-reduced-motion: no-preference) {
    .notes__fab-paper {
      transition:
        transform 200ms ease-out,
        rotate 200ms ease-out,
        scale 160ms ease-out,
        filter 160ms ease-out;
    }

    .notes__fab-paper::before {
      rotate: 0deg;
      transition: rotate 160ms ease-out;
    }

    /* Picked up: bigger, with a deeper shadow, the pin pulled out a little. */
    .notes[data-lift] .notes__fab-paper::before {
      rotate: -6deg;
    }
  }

  /* While held, the tilt follows the drag per frame, so it must not lag. */
  .notes[data-lift] .notes__fab-paper {
    scale: 1.12;
    filter: var(--sticky-shadows-lift);
    transition:
      scale 160ms ease-out,
      filter 160ms ease-out;
  }

  .notes[data-lift] .notes__fab,
  .notes[data-lift] .notes__fab:hover {
    translate: none;
    transition: none;
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
    bottom: var(--notes-card-bottom, var(--notes-bottom));
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

  /* On the left edge the desktop card anchors to the left, too. */
  @media (min-width: 761px) {
    .notes[data-side="left"] .notes__card {
      right: auto;
      left: var(--notes-left);
    }
  }

  @media (max-width: 760px) {
    .notes {
      --notes-right: max(0.6rem, env(safe-area-inset-right));
      --notes-left: max(0.6rem, env(safe-area-inset-left));
      --notes-bottom-limit: calc(
        var(--app-mobile-nav-height, 57px) +
          var(
            --app-mobile-nav-bottom-inset,
            max(4px, env(safe-area-inset-bottom, 0px))
          ) +
          1rem
      );
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
