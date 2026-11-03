/** Pure helpers behind the header account menu (popover on desktop, bottom sheet on phones). */

/** Viewport width at or below which the menu becomes a bottom sheet. */
export const SHEET_MEDIA_QUERY = "(max-width: 640px)";

/** A precise hovering pointer (a mouse), as opposed to a touch screen. */
export const POINTER_MEDIA_QUERY = "(hover: hover) and (pointer: fine)";

const SWIPE_DISTANCE_PX = 80;
const SWIPE_VELOCITY_PX_PER_MS = 0.6;
const SWIPE_FLICK_MIN_PX = 24;

/** Index of the next menu item for an arrow, Home or End key, or null for other keys. */
export function nextMenuIndex(
  current: number,
  count: number,
  key: string,
): number | null {
  if (count <= 0) return null;
  switch (key) {
    case "ArrowDown":
      return current < 0 ? 0 : (current + 1) % count;
    case "ArrowUp":
      return current < 0 ? count - 1 : (current - 1 + count) % count;
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
}

/** Whether a downward drag of the sheet should dismiss it instead of snapping back. */
export function shouldDismissSheet(deltaY: number, elapsedMs: number): boolean {
  if (deltaY <= 0) return false;
  if (deltaY >= SWIPE_DISTANCE_PX) return true;
  return (
    deltaY >= SWIPE_FLICK_MIN_PX &&
    elapsedMs > 0 &&
    deltaY / elapsedMs >= SWIPE_VELOCITY_PX_PER_MS
  );
}

/** Next focus target for Tab inside a trap, wrapping at both ends; null lets the browser decide. */
export function trapTabIndex(
  current: number,
  count: number,
  backwards: boolean,
): number | null {
  if (count <= 0) return null;
  if (current < 0) return backwards ? count - 1 : 0;
  if (backwards && current === 0) return count - 1;
  if (!backwards && current === count - 1) return 0;
  return null;
}
