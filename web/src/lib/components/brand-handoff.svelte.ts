/**
 * Home shows a big mascot in its greeting. As the page scrolls, that mascot
 * shrinks and travels into the header's logo slot (like an iOS large title
 * collapsing into the bar) while the slot grows from nothing and pushes the
 * wordmark right. The Home dashboard registers its hero element here; the
 * brand header runs the scroll-linked morph. The maths is pure and lives in
 * this module so it can be tested.
 */

export type Rect = { left: number; top: number; size: number };

/**
 * The hero mascot's element, set by the page that owns it. Without a hero
 * (every other route) the header mark is simply always shown.
 */
export const handoff = $state<{ hero: HTMLElement | null }>({ hero: null });

export function setHero(hero: HTMLElement | null) {
  handoff.hero = hero;
}

/** Smallest scroll distance the morph takes, so short layouts still animate. */
export const MIN_MORPH_DISTANCE = 120;

export function clamp01(value: number): number {
  if (!(value > 0)) return 0;
  return value > 1 ? 1 : value;
}

/**
 * How far the morph runs, in pixels of scroll: until the hero's natural
 * position has scrolled up to the header slot, i.e. under the header.
 */
export function morphDistance(heroDocTop: number, slotTop: number): number {
  return Math.max(MIN_MORPH_DISTANCE, heroDocTop - slotTop);
}

/** Linear scroll progress in [0, 1]. */
export function scrollProgress(scrollY: number, distance: number): number {
  if (distance <= 0) return 1;
  return clamp01(scrollY / distance);
}

/** Smoothstep: eases in and out, with matching endpoints. */
export function ease(p: number): number {
  const x = clamp01(p);
  return x * x * (3 - 2 * x);
}

export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

export function lerpRect(from: Rect, to: Rect, t: number): Rect {
  return {
    left: lerp(from.left, to.left, t),
    top: lerp(from.top, to.top, t),
    size: lerp(from.size, to.size, t),
  };
}

/**
 * Transform that places a box laid out at (0, 0) with size `size` onto the
 * interpolated rect: translate, then scale, both about the top-left corner.
 */
export function morphTransform(
  from: Rect,
  to: Rect,
  t: number,
  size: number,
): { x: number; y: number; scale: number } {
  const rect = lerpRect(from, to, t);
  return { x: rect.left, y: rect.top, scale: rect.size / size };
}

/** Header slot width as a share of its full width. Reduced motion is a step. */
export function slotProgress(progress: number, reducedMotion: boolean): number {
  if (reducedMotion) return progress >= 1 ? 1 : 0;
  return ease(progress);
}
