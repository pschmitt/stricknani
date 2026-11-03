/**
 * Home shows a big mascot in its greeting, so the header brand mark stays out
 * of the way until the greeting mascot has scrolled mostly out of view. The
 * Home dashboard reports how much of its mascot is visible; the brand header
 * reads whether it should show its own mark and flies it in or out.
 */

/** The header mark appears once less than this share of the hero is visible. */
export const HERO_VISIBLE_THRESHOLD = 0.3;

/** Whether the header brand mark is shown. Without a hero it always is. */
export function brandMarkShown(
  heroActive: boolean,
  heroRatio: number,
  threshold = HERO_VISIBLE_THRESHOLD,
): boolean {
  return !heroActive || heroRatio < threshold;
}

export type HeroRect = { left: number; top: number; size: number };

/**
 * The hero mascot's element, set by the page that owns it. The header flies
 * its mark from/to this element's box.
 */
export const handoff = $state<{
  heroActive: boolean;
  heroRatio: number;
  hero: HTMLElement | null;
  /** False for changes that are not scrolling (page entry, restored scroll). */
  animate: boolean;
}>({ heroActive: false, heroRatio: 1, hero: null, animate: false });

export function setHero(hero: HTMLElement | null) {
  handoff.hero = hero;
  handoff.heroActive = hero !== null;
  handoff.heroRatio = 1;
  handoff.animate = false;
}

export function setHeroRatio(ratio: number, animate: boolean) {
  handoff.animate = animate;
  handoff.heroRatio = ratio;
}

/**
 * FLIP transform that puts a box of `to` size at `from`: translate, then
 * scale, both about the box's top-left corner.
 */
export function flipBetween(from: HeroRect, to: HeroRect) {
  return {
    dx: from.left - to.left,
    dy: from.top - to.top,
    scale: from.size / to.size,
  };
}
