/**
 * Pure math of the photo viewer zoom. The image is transformed with
 * `translate(x, y) scale(scale)` around the centre of its frame, so every
 * coordinate here is relative to that centre (pointer position minus frame
 * centre) and `x`/`y` are screen pixels.
 */
export const MIN_SCALE = 1;
export const MAX_SCALE = 5;
/** Scale reached by a double click or tap on the fitted photo. */
export const DOUBLE_CLICK_SCALE = 2;
/** Multiplier per keyboard zoom step (+ / -). */
export const KEY_ZOOM_FACTOR = 1.5;

const LINE_HEIGHT_PX = 16;
const PAGE_HEIGHT_PX = 800;
const MAX_WHEEL_PX = 120;
const WHEEL_SENSITIVITY = 0.004;

export interface ZoomState {
  scale: number;
  x: number;
  y: number;
}

export interface Size {
  width: number;
  height: number;
}

export const IDENTITY: ZoomState = { scale: MIN_SCALE, x: 0, y: 0 };

export function clampScale(scale: number): number {
  if (!Number.isFinite(scale)) return MIN_SCALE;
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
}

/** Wheel delta in pixels, whatever the `deltaMode` (0 pixel, 1 line, 2 page). */
export function wheelDeltaPixels(deltaY: number, deltaMode: number): number {
  if (deltaMode === 1) return deltaY * LINE_HEIGHT_PX;
  if (deltaMode === 2) return deltaY * PAGE_HEIGHT_PX;
  return deltaY;
}

/** Multiplier for one wheel event: scrolling up (negative) zooms in. */
export function wheelZoomFactor(deltaY: number, deltaMode: number): number {
  const px = wheelDeltaPixels(deltaY, deltaMode);
  if (!Number.isFinite(px)) return 1;
  const limited = Math.min(MAX_WHEEL_PX, Math.max(-MAX_WHEEL_PX, px));
  return Math.exp(-limited * WHEEL_SENSITIVITY);
}

/**
 * Largest pan offset per axis: the zoomed content may move until its edge
 * meets the frame edge. Content that is smaller than the frame stays centred.
 */
export function maxPan(scale: number, content: Size, view: Size): Size {
  return {
    width: Math.max(0, (content.width * scale - view.width) / 2),
    height: Math.max(0, (content.height * scale - view.height) / 2),
  };
}

export function clampPan(
  state: ZoomState,
  content: Size,
  view: Size,
): ZoomState {
  const limit = maxPan(state.scale, content, view);
  const x = Math.min(limit.width, Math.max(-limit.width, state.x));
  const y = Math.min(limit.height, Math.max(-limit.height, state.y));
  return { scale: state.scale, x: x + 0, y: y + 0 };
}

/**
 * Zoom to `nextScale` keeping the content point under `anchor` where it is.
 * Back at 1x the photo is centred again.
 */
export function zoomAt(
  state: ZoomState,
  nextScale: number,
  anchor: { x: number; y: number },
  content: Size,
  view: Size,
): ZoomState {
  const scale = clampScale(nextScale);
  if (scale <= MIN_SCALE) return { scale: MIN_SCALE, x: 0, y: 0 };
  const ratio = scale / state.scale;
  return clampPan(
    {
      scale,
      x: anchor.x - (anchor.x - state.x) * ratio,
      y: anchor.y - (anchor.y - state.y) * ratio,
    },
    content,
    view,
  );
}

/** Pan by a pointer movement, clamped. */
export function panBy(
  state: ZoomState,
  dx: number,
  dy: number,
  content: Size,
  view: Size,
): ZoomState {
  if (state.scale <= MIN_SCALE) return state;
  return clampPan(
    { scale: state.scale, x: state.x + dx, y: state.y + dy },
    content,
    view,
  );
}

/** Double click: back to fit when zoomed, else 2x at the clicked point. */
export function toggleZoom(
  state: ZoomState,
  anchor: { x: number; y: number },
  content: Size,
  view: Size,
): ZoomState {
  if (state.scale > MIN_SCALE) return IDENTITY;
  return zoomAt(state, DOUBLE_CLICK_SCALE, anchor, content, view);
}

/** Keyboard zoom: `+`/`=` in, `-` out, `0` reset; null for other keys. */
export function zoomKeyScale(key: string, scale: number): number | null {
  switch (key) {
    case "+":
    case "=":
      return clampScale(scale * KEY_ZOOM_FACTOR);
    case "-":
    case "_":
      return clampScale(scale / KEY_ZOOM_FACTOR);
    case "0":
      return MIN_SCALE;
    default:
      return null;
  }
}
