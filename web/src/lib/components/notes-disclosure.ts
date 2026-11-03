/**
 * State machine behind the floating notes note: a small FAB that morphs into
 * the full note card and back. It owns the phases and focus rules only; the
 * component supplies the animation (`run`) and the focus targets, so the
 * logic stays testable without a DOM.
 */

export type NotesPhase = "closed" | "opening" | "open" | "closing";

export type NotesDirection = "open" | "close";

export type NotesDisclosureDeps = {
  /**
   * Plays the morph. Resolves true when it ran to the end and false when it
   * was reversed or cancelled part-way (a later call took over).
   */
  run: (direction: NotesDirection) => Promise<boolean>;
  onPhase: (phase: NotesPhase) => void;
  focusCard: () => void;
  focusFab: () => void;
  /**
   * Asked when a close has finished. True when focus is still in the card (or
   * nowhere), so it goes back to the FAB; false when the person has moved on.
   */
  shouldRestoreFocus: () => boolean;
};

export type NotesDisclosure = {
  readonly phase: NotesPhase;
  open: () => void;
  close: () => void;
  toggle: () => void;
  /** Handles an Escape key press. Returns true when it closed the note. */
  handleKey: (key: string) => boolean;
};

export function createNotesDisclosure(
  deps: NotesDisclosureDeps,
): NotesDisclosure {
  let phase: NotesPhase = "closed";

  function set(next: NotesPhase) {
    phase = next;
    deps.onPhase(next);
  }

  function open() {
    if (phase === "opening" || phase === "open") return;
    set("opening");
    deps.focusCard();
    void deps.run("open").then((finished) => {
      if (finished && phase === "opening") set("open");
    });
  }

  function close() {
    if (phase === "closed" || phase === "closing") return;
    set("closing");
    void deps.run("close").then((finished) => {
      if (!finished || phase !== "closing") return;
      set("closed");
      if (deps.shouldRestoreFocus()) deps.focusFab();
    });
  }

  return {
    get phase() {
      return phase;
    },
    open,
    close,
    toggle() {
      if (phase === "opening" || phase === "open") close();
      else open();
    },
    handleKey(key) {
      if (key !== "Escape" || (phase !== "open" && phase !== "opening")) {
        return false;
      }
      close();
      return true;
    },
  };
}

/**
 * Damped sway of the collapsed note around its pin: rotation deltas from the
 * resting tilt, as keyframe offsets. Mirrored on Android as a spring.
 */
export const SWAY_KEYFRAMES: ReadonlyArray<{
  offset: number;
  delta: number;
}> = [
  { offset: 0, delta: 0 },
  { offset: 0.18, delta: 5 },
  { offset: 0.38, delta: -3 },
  { offset: 0.56, delta: 2 },
  { offset: 0.74, delta: -1.2 },
  { offset: 0.88, delta: 0.5 },
  { offset: 1, delta: 0 },
];

export const FAB_TILT = 4;
export const CARD_TILT = -1.5;
export const MORPH_MS = 380;
export const MORPH_EASING = "cubic-bezier(0.3, 1.3, 0.5, 1)";
export const SWAY_MS = 600;
export const SWAY_SETTLE_MS = 180;

/** Geometry for the FLIP start state: the card's start pose over the FAB. */
export function flipFrom(
  fab: { left: number; top: number; width: number },
  card: { left: number; top: number; width: number; height: number },
  fabHeight: number,
) {
  return {
    dx: fab.left + fab.width / 2 - (card.left + card.width / 2),
    dy: fab.top - card.top,
    sx: fab.width / card.width,
    sy: fabHeight / card.height,
  };
}
