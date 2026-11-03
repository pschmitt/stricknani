// Pure reaction logic for the mascot: which reaction plays next, the queue,
// the tap counter and easter egg, and the pose of each reaction over time.

export const REACTIONS = [
  "squish",
  "wink",
  "spin",
  "hearts",
  "giggle",
] as const;
export type Reaction = (typeof REACTIONS)[number];
export type AnimKind = Reaction | "bounce";

export const REACTION_DURATION: Record<AnimKind, number> = {
  squish: 560,
  wink: 760,
  spin: 800,
  hearts: 700,
  giggle: 650,
  bounce: 420,
};

/** Rapid taps count as a streak when each follows the last within this gap. */
export const TAP_GAP_MS = 450;
export const EASTER_EGG_TAPS = 7;
const SEQUENCE_PROBABILITY = 0.7;

/**
 * Next reaction: usually the one after `last` in sequence (70%), otherwise a
 * random different one. The first reaction is always the first in sequence.
 */
export function nextReaction(
  last: Reaction | null,
  rand: () => number,
): Reaction {
  if (last === null) return REACTIONS[0];
  const index = REACTIONS.indexOf(last);
  if (rand() < SEQUENCE_PROBABILITY) {
    return REACTIONS[(index + 1) % REACTIONS.length];
  }
  const others = REACTIONS.filter((reaction) => reaction !== last);
  const pick = Math.min(others.length - 1, Math.floor(rand() * others.length));
  return others[pick];
}

export type Playback = {
  current: { kind: Reaction; startedAt: number } | null;
  queued: Reaction | null;
};

export const idlePlayback: Playback = { current: null, queued: null };

/** True when the current reaction has run its course at `now`. */
function finished(playback: Playback, now: number): boolean {
  const { current } = playback;
  return (
    current === null ||
    now >= current.startedAt + REACTION_DURATION[current.kind]
  );
}

/**
 * Ask for a reaction. Starts it if nothing is playing, otherwise queues it;
 * the queue holds at most one, extra requests are dropped.
 */
export function requestReaction(
  playback: Playback,
  kind: Reaction,
  now: number,
): Playback {
  if (finished(playback, now)) {
    return { current: { kind, startedAt: now }, queued: null };
  }
  if (playback.queued !== null) return playback;
  return { ...playback, queued: kind };
}

/** Move on when the current reaction is done: start the queued one, or go idle. */
export function advancePlayback(playback: Playback, now: number): Playback {
  if (playback.current === null || !finished(playback, now)) return playback;
  const { current, queued } = playback;
  if (queued !== null) {
    const startedAt = current.startedAt + REACTION_DURATION[current.kind];
    return {
      current: { kind: queued, startedAt: Math.min(startedAt, now) },
      queued: null,
    };
  }
  return idlePlayback;
}

export type TapState = { count: number; lastAt: number | null };
export const initialTapState: TapState = { count: 0, lastAt: null };

/**
 * Count a tap. Taps within `gap` of each other build a streak; the seventh
 * triggers the easter egg (only when `allowed`, e.g. once per session).
 * The streak restarts after it fires.
 */
export function registerTap(
  state: TapState,
  now: number,
  allowed = true,
  gap = TAP_GAP_MS,
  threshold = EASTER_EGG_TAPS,
): { state: TapState; easterEgg: boolean; quick: boolean } {
  const quick = state.lastAt !== null && now - state.lastAt <= gap;
  const count = quick ? state.count + 1 : 1;
  if (allowed && count >= threshold) {
    return { state: { count: 0, lastAt: null }, easterEgg: true, quick };
  }
  return { state: { count, lastAt: now }, easterEgg: false, quick };
}

export type EyeMode = "normal" | "happy" | "swap" | "dizzy" | "blink";

export type Pose = {
  /** Squash and stretch about the ball's base. */
  sx: number;
  sy: number;
  /** Extra rotation in degrees. */
  rot: number;
  /** Vertical hop in viewBox units (negative is up). */
  dy: number;
  eyes: EyeMode;
  /** 0 to 1: how rosy the cheeks are. */
  cheek: number;
};

export const restPose: Pose = {
  sx: 1,
  sy: 1,
  rot: 0,
  dy: 0,
  eyes: "normal",
  cheek: 0,
};

const TAU = Math.PI * 2;

function springy(p: number, decay: number, cycles: number): number {
  return Math.exp(-decay * p) * Math.cos(TAU * cycles * p);
}

function easeOutBack(p: number): number {
  const c1 = 1.4;
  const c3 = c1 + 1;
  return 1 + c3 * (p - 1) ** 3 + c1 * (p - 1) ** 2;
}

/** The pose of an animation at progress `p` (0 to 1). */
export function poseAt(kind: AnimKind, p: number): Pose {
  const t = Math.min(1, Math.max(0, p));
  switch (kind) {
    case "squish": {
      const k = springy(t, 4, 2.2);
      return { ...restPose, sx: 1 + 0.15 * k, sy: 1 - 0.15 * k };
    }
    case "wink": {
      const eyes: EyeMode = t < 0.3 ? "happy" : t < 0.85 ? "swap" : "normal";
      const k = springy(t, 4, 1.5);
      return {
        ...restPose,
        eyes,
        rot: 4 * Math.sin(Math.PI * t) * (t < 0.5 ? -1 : 1),
        sx: 1 + 0.04 * k,
        sy: 1 - 0.04 * k,
      };
    }
    case "spin":
      return {
        ...restPose,
        rot: 360 * easeOutBack(t),
        dy: -5 * Math.sin(Math.PI * t),
        eyes: t > 0.15 && t < 0.8 ? "happy" : "normal",
      };
    case "hearts": {
      const k = springy(t, 5, 2);
      return {
        ...restPose,
        sx: 1 + 0.07 * k,
        sy: 1 - 0.07 * k,
        eyes: "happy",
        cheek: 1 - t,
      };
    }
    case "giggle":
      return {
        ...restPose,
        rot: 6 * Math.sin(TAU * 5 * t) * (1 - t),
        dy: -0.8 * Math.abs(Math.sin(TAU * 4 * t)) * (1 - t),
        eyes: t < 0.8 ? "happy" : "normal",
        cheek: Math.sin(Math.PI * Math.min(1, t * 1.2)),
      };
    case "bounce": {
      const hop = Math.sin(Math.PI * t);
      return {
        ...restPose,
        dy: -3 * hop,
        sy: 1 + 0.04 * hop,
        sx: 1 - 0.03 * hop,
      };
    }
  }
}

export type Particle = {
  kind: "heart" | "yarn";
  /** Start offset and travel, in viewBox units. */
  x0: number;
  dx: number;
  dy: number;
  size: number;
  delay: number;
  color: string;
};

const HEART_COLORS = ["#ff6f9f", "#ff8fb4", "#ff5a8f"];
const YARN_COLORS = ["#8cb8ff", "#2f6fed", "#ff6f9f", "#ffd36b", "#7be0b0"];

/** 3 to 5 pink hearts that float up from above the ball. */
export function heartParticles(rand: () => number): Particle[] {
  const count = 3 + Math.min(2, Math.floor(rand() * 3));
  return Array.from({ length: count }, (_, i) => ({
    kind: "heart" as const,
    x0: 54 + (i - (count - 1) / 2) * 7,
    dx: (rand() - 0.5) * 10,
    dy: -(16 + rand() * 10),
    size: 5 + rand() * 3,
    delay: Math.round(i * 70 + rand() * 40),
    color: HEART_COLORS[i % HEART_COLORS.length],
  }));
}

/** The easter egg: a burst of tiny yarn balls in every direction. */
export function confettiParticles(rand: () => number, count = 24): Particle[] {
  return Array.from({ length: count }, (_, i) => {
    const angle = (i / count) * TAU + (rand() - 0.5) * 0.5;
    const radius = 24 + rand() * 22;
    return {
      kind: "yarn" as const,
      x0: 54,
      dx: Math.cos(angle) * radius,
      dy: Math.sin(angle) * radius - 6,
      size: 2.6 + rand() * 2.2,
      delay: Math.round(rand() * 120),
      color: YARN_COLORS[i % YARN_COLORS.length],
    };
  });
}
