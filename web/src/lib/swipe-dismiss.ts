/** Pure decisions behind the swipe-to-dismiss cards (see `dismiss-card.ts`). */

/** Share of the card's width a drag must pass to dismiss it. */
export const DISMISS_FRACTION = 0.38;
/** A flick faster than this (px/ms) dismisses even a short drag. */
export const FLING_VELOCITY = 0.7;
/** ...as long as it travelled at least this far (px), so a twitch never counts. */
export const FLING_MIN_DISTANCE = 28;
/** Movement (px) before we decide whether a gesture is a scroll or a swipe. */
export const LOCK_SLOP = 8;
/** Horizontal movement must beat vertical by this factor to lock a swipe. */
export const LOCK_RATIO = 1.5;

export type Axis = "horizontal" | "vertical" | undefined;

/** Decide a gesture's axis once it moved past the slop; undecided until then. */
export function lockAxis(dx: number, dy: number): Axis {
  const ax = Math.abs(dx);
  const ay = Math.abs(dy);
  if (Math.max(ax, ay) < LOCK_SLOP) return undefined;
  return ax > ay * LOCK_RATIO
    ? "horizontal"
    : ay >= ax
      ? "vertical"
      : undefined;
}

export type SwipeOutcome =
  { kind: "dismiss"; direction: 1 | -1 } | { kind: "restore" };

/** What to do on release: dismiss past the threshold or on a fling, else spring back. */
export function swipeOutcome(opts: {
  dx: number;
  width: number;
  /** Horizontal velocity in px/ms at release (signed). */
  velocity: number;
}): SwipeOutcome {
  const { dx, width, velocity } = opts;
  if (width <= 0 || dx === 0) return { kind: "restore" };
  const direction = dx > 0 ? 1 : -1;
  if (Math.abs(dx) >= width * DISMISS_FRACTION) {
    return { kind: "dismiss", direction };
  }
  const sameWay = Math.sign(velocity) === direction;
  if (
    sameWay &&
    Math.abs(velocity) >= FLING_VELOCITY &&
    Math.abs(dx) >= FLING_MIN_DISTANCE
  ) {
    return { kind: "dismiss", direction };
  }
  return { kind: "restore" };
}

/** How the card looks while the finger drags it: it tilts and fades with distance. */
export function dragLook(
  dx: number,
  width: number,
): { rotate: number; opacity: number } {
  const progress = width > 0 ? Math.min(1, Math.abs(dx) / width) : 0;
  return {
    rotate: Math.sign(dx) * progress * 12,
    opacity: Math.max(0.25, 1 - progress * 0.9),
  };
}

export type DismissParticle = {
  kind: "heart" | "yarn";
  dx: number;
  dy: number;
  size: number;
  delay: number;
  color: string;
};

const COLORS = ["#ff6f9f", "#ff8fb4", "#8cb8ff", "#ffd36b", "#7be0b0"];

/** A small puff of hearts and yarn balls; the pick is deterministic for a given `rand`. */
export function dismissParticles(
  rand: () => number,
  count = 9,
): DismissParticle[] {
  return Array.from({ length: count }, (_, i) => {
    const angle = (i / count) * Math.PI * 2 + (rand() - 0.5) * 0.6;
    const radius = 36 + rand() * 40;
    return {
      kind: i % 3 === 0 ? ("heart" as const) : ("yarn" as const),
      dx: Math.cos(angle) * radius,
      dy: Math.sin(angle) * radius - 14,
      size: 7 + rand() * 7,
      delay: Math.round(rand() * 90),
      color: COLORS[i % COLORS.length],
    };
  });
}
