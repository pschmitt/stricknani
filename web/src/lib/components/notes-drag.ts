/**
 * Pure logic behind moving the notes FAB: the pick-up state machine, snap and
 * clamp math, keyboard steps, tilt smoothing and persistence. The component
 * owns the DOM and the animations; the numbers here are shared with Android.
 */

export type Side = "left" | "right";

/** Where the FAB rests: an edge and a fraction (0..1) of the allowed band. */
export type Placement = { side: Side; y: number };

export type PointerKind = "touch" | "mouse" | "pen";

// Pick-up
export const LONG_PRESS_MS = 400;
export const TOUCH_SLOP_PX = 8;
export const MOUSE_SLOP_PX = 6;

// Lifted look
export const LIFT_SCALE = 1.12;
export const LIFT_MS = 160;
export const LIFT_SHADOW_OFFSET_PX = 4;
export const LIFT_PIN_TILT = -6;

// Tilt while dragging
export const TILT_PER_VELOCITY = 0.02; // degrees per px/s
export const TILT_MAX = 12;
export const TILT_TAU_MS = 80;
export const REST_TILT = 4;

// Drop
export const SNAP_MS = 420;
export const SNAP_EASING = "cubic-bezier(0.3, 1.3, 0.5, 1)";
export const SNAP_REDUCED_MS = 120;

// Keyboard
export const KEY_STEP = 0.08;

export const STORAGE_KEY = "stricknani.notes-fab";

const clamp = (value: number, min: number, max: number) =>
  Math.min(Math.max(value, min), max);

/** Resting rotation of the mini note: mirrored on the left edge. */
export function restTilt(side: Side): number {
  return side === "left" ? -REST_TILT : REST_TILT;
}

/** Target rotation (degrees) for a horizontal velocity in px per second. */
export function velocityTilt(vx: number): number {
  return clamp(vx * TILT_PER_VELOCITY, -TILT_MAX, TILT_MAX);
}

/** One exponential smoothing step towards `target` over `dtMs`. */
export function smoothTilt(current: number, target: number, dtMs: number) {
  if (dtMs <= 0) return current;
  return current + (target - current) * (1 - Math.exp(-dtMs / TILT_TAU_MS));
}

/** Frame-by-frame tilt from the pointer x position; decays when it stops. */
export function createTiltTracker() {
  let prevX: number | null = null;
  let prevT = 0;
  let tilt = 0;
  return {
    frame(x: number, t: number): number {
      if (prevX === null) {
        prevX = x;
        prevT = t;
        return tilt;
      }
      const dt = t - prevT;
      if (dt <= 0) return tilt;
      const vx = ((x - prevX) / dt) * 1000;
      tilt = smoothTilt(tilt, velocityTilt(vx), dt);
      prevX = x;
      prevT = t;
      return tilt;
    },
  };
}

/** Viewport numbers the geometry needs, all in CSS px. */
export type FabGeometry = {
  width: number;
  height: number;
  /** Edge length of the (square) FAB. */
  size: number;
  /** Top of the allowed band: safe area plus header plus 16px. */
  top: number;
  /** Distance from the viewport bottom to the lowest FAB edge. */
  bottomLimit: number;
  insetLeft: number;
  insetRight: number;
};

/** Height of the band the FAB top-left corner may travel in. */
export function bandHeight(g: FabGeometry): number {
  return Math.max(0, g.height - g.bottomLimit - g.size - g.top);
}

/** Top-left corner of the FAB for a placement. */
export function placementToPoint(p: Placement, g: FabGeometry) {
  return {
    x: p.side === "left" ? g.insetLeft : g.width - g.insetRight - g.size,
    y: g.top + clamp(p.y, 0, 1) * bandHeight(g),
  };
}

/** Fraction of the band for a FAB top edge at `y`. */
export function yFraction(y: number, g: FabGeometry): number {
  const band = bandHeight(g);
  return band === 0 ? 0 : clamp((y - g.top) / band, 0, 1);
}

/** Keeps the dragged FAB inside the viewport. */
export function clampToViewport(
  point: { x: number; y: number },
  g: FabGeometry,
) {
  return {
    x: clamp(point.x, 0, Math.max(0, g.width - g.size)),
    y: clamp(point.y, 0, Math.max(0, g.height - g.size)),
  };
}

/** Nearest horizontal edge and clamped vertical fraction for a dropped FAB. */
export function snapPlacement(
  point: { x: number; y: number },
  g: FabGeometry,
): Placement {
  const centre = point.x + g.size / 2;
  return {
    side: centre < g.width / 2 ? "left" : "right",
    y: yFraction(point.y, g),
  };
}

/** The default placement: right edge, at the CSS default top position. */
export function defaultPlacement(defaultTop: number, g: FabGeometry) {
  return { side: "right", y: yFraction(defaultTop, g) } satisfies Placement;
}

export type MoveKey = "ArrowUp" | "ArrowDown" | "ArrowLeft" | "ArrowRight";

export const isMoveKey = (key: string): key is MoveKey =>
  key === "ArrowUp" ||
  key === "ArrowDown" ||
  key === "ArrowLeft" ||
  key === "ArrowRight";

/** Alt+Arrow: 8% steps vertically, left and right switch the side. */
export function stepPlacement(p: Placement, key: MoveKey): Placement {
  switch (key) {
    case "ArrowUp":
      return { ...p, y: clamp(p.y - KEY_STEP, 0, 1) };
    case "ArrowDown":
      return { ...p, y: clamp(p.y + KEY_STEP, 0, 1) };
    case "ArrowLeft":
      return { ...p, side: "left" };
    case "ArrowRight":
      return { ...p, side: "right" };
  }
}

export type MoveAnnouncement = "left" | "right" | "up" | "down";

/** What moved, or null when the placement did not change. */
export function describeMove(
  from: Placement,
  to: Placement,
): MoveAnnouncement | null {
  if (from.side !== to.side) return to.side;
  if (to.y < from.y) return "up";
  if (to.y > from.y) return "down";
  return null;
}

/**
 * Bottom offset for the open card: as near the FAB as possible, but fully
 * inside the band (below the header, above the bottom limit).
 */
export function fitCardBottom(
  fabBottom: number,
  cardHeight: number,
  g: FabGeometry,
): number {
  const highest = g.height - g.top - cardHeight;
  return Math.max(g.bottomLimit, Math.min(fabBottom, highest));
}

export function parsePlacement(raw: string | null): Placement | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) return null;
    const { side, y } = value as Record<string, unknown>;
    if (side !== "left" && side !== "right") return null;
    if (typeof y !== "number" || !Number.isFinite(y)) return null;
    return { side, y: clamp(y, 0, 1) };
  } catch {
    return null;
  }
}

type StorageLike = Pick<Storage, "getItem" | "setItem">;

export function loadPlacement(storage?: StorageLike): Placement | null {
  try {
    return parsePlacement((storage ?? localStorage).getItem(STORAGE_KEY));
  } catch {
    return null;
  }
}

export function savePlacement(p: Placement, storage?: StorageLike): void {
  try {
    (storage ?? localStorage).setItem(STORAGE_KEY, JSON.stringify(p));
  } catch {
    // Private mode or blocked storage: the position just is not remembered.
  }
}

export type DragOutcome = "tap" | "drop" | "none";

/**
 * Pick-up state machine. Touch needs a still press of 400ms (the host calls
 * `longPress` from its timer); a fine pointer picks up after 6px of travel.
 */
export function createFabDrag() {
  let phase: "idle" | "pressed" | "dragging" = "idle";
  let kind: PointerKind = "mouse";
  let originX = 0;
  let originY = 0;
  let anchorX = 0;
  let anchorY = 0;
  let curX = 0;
  let curY = 0;

  return {
    get phase() {
      return phase;
    },
    get dragging() {
      return phase === "dragging";
    },
    /** Pointer offset since the anchor: the FAB follows it 1:1. */
    get offset() {
      return { dx: curX - anchorX, dy: curY - anchorY };
    },
    /** Returns the long-press delay to arm, or null for fine pointers. */
    down(pointer: PointerKind, x: number, y: number): number | null {
      phase = "pressed";
      kind = pointer;
      originX = anchorX = curX = x;
      originY = anchorY = curY = y;
      return pointer === "touch" ? LONG_PRESS_MS : null;
    },
    /** "pickup" when this move lifted it, "cancel" when a touch moved too far. */
    move(x: number, y: number): "pickup" | "cancel" | "drag" | "none" {
      curX = x;
      curY = y;
      if (phase === "dragging") return "drag";
      if (phase !== "pressed") return "none";
      const dist = Math.hypot(x - originX, y - originY);
      if (kind === "touch") {
        if (dist > TOUCH_SLOP_PX) {
          phase = "idle";
          return "cancel";
        }
        return "none";
      }
      if (dist > MOUSE_SLOP_PX) {
        phase = "dragging";
        return "pickup";
      }
      return "none";
    },
    /** The long-press timer fired. True when the FAB was picked up. */
    longPress(): boolean {
      if (phase !== "pressed" || kind !== "touch") return false;
      phase = "dragging";
      anchorX = curX;
      anchorY = curY;
      return true;
    },
    up(): DragOutcome {
      const was = phase;
      phase = "idle";
      if (was === "dragging") return "drop";
      return was === "pressed" ? "tap" : "none";
    },
    cancel(): boolean {
      const was = phase === "dragging";
      phase = "idle";
      return was;
    },
  };
}
