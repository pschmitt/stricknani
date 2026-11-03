import { msg } from "#lib/i18n.svelte.ts";

export type GreetingSlot = "morning" | "day" | "evening" | "night";

/** Every slot has exactly this many greetings; Android uses the same count. */
export const GREETING_POOL_SIZE = 5;

/** The greetings per time of day; msgids are English, German lives in the catalog. */
export const GREETINGS: Record<GreetingSlot, readonly string[]> = {
  morning: [
    msg("Good morning!"),
    msg("Morning, sunshine!"),
    msg("Rise and knit!"),
    msg("Mooorning!"),
    msg("Up already?"),
  ],
  day: [
    msg("Hello there!"),
    msg("Hellooo!"),
    msg("Hey hey!"),
    msg("Howdy, knitter!"),
    msg("Well, hi!"),
  ],
  evening: [
    msg("Good evening!"),
    msg("Evening, knitter!"),
    msg("Cosy evening?"),
    msg("Hey, night is near!"),
    msg("Evening!"),
  ],
  night: [
    msg("Still knitting?"),
    msg("Hello, night owl!"),
    msg("Still awake?"),
    msg("Just one more row?"),
    msg("Shh, everyone is asleep!"),
  ],
};

/** Morning 5-11, day 12-17, evening 18-22, night otherwise. */
export function greetingSlot(hour: number): GreetingSlot {
  if (hour >= 5 && hour < 12) return "morning";
  if (hour >= 12 && hour < 18) return "day";
  if (hour >= 18 && hour < 23) return "evening";
  return "night";
}

/** A random pool index; `random` is injectable for tests. */
export function pickGreetingIndex(random: () => number = Math.random): number {
  return Math.min(
    GREETING_POOL_SIZE - 1,
    Math.floor(random() * GREETING_POOL_SIZE),
  );
}

// One pick per page visit: module state survives re-renders and theme switches,
// and a fresh load (or a new session) draws again.
let visitIndex: number | null = null;

export function visitGreetingIndex(): number {
  visitIndex ??= pickGreetingIndex();
  return visitIndex;
}

/** The English greeting msgid for an hour and pool index. */
export function greetingFor(hour: number, index: number): string {
  return GREETINGS[greetingSlot(hour)][index] ?? GREETINGS.day[0];
}

/** Test hook: forget the pick of this visit. */
export function resetVisitGreeting(): void {
  visitIndex = null;
}
