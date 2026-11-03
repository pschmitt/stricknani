// Pure physics helpers for the fidgetable mascot. No DOM, no clocks, no
// randomness of their own: time steps and random numbers are passed in.

/** How far (px) the ball may be pulled away from its resting place. */
export const RUBBER_LIMIT = 24;
/** Largest pupil/eye offset (viewBox units) for cursor tracking. */
export const EYE_MAX = 1.5;
/** Minimum release speed (px/s) that counts as a flick. */
export const FLICK_MIN_SPEED = 600;
/** Spin speed (deg/s) above which the eyes go dizzy. */
export const DIZZY_OMEGA = 360;
/** Highest spin speed (deg/s) a flick or repeated taps can reach. */
export const MAX_OMEGA = 1800;
/** Friction coefficient: omega *= exp(-k dt). */
export const SPIN_FRICTION = 2.2;

export type Vec = { x: number; y: number };
export type Spring = { x: number; v: number };
export type Spin = { angle: number; omega: number };
export type Fidget = "blink" | "look-around" | "micro-bounce";

/** Maps a raw drag distance to a displacement that approaches `limit` asymptotically. */
export function rubberBand(offset: number, limit = RUBBER_LIMIT): number {
  if (offset === 0 || limit <= 0) return 0;
  return Math.sign(offset) * limit * (1 - Math.exp(-Math.abs(offset) / limit));
}

/** Rubber-band a 2D drag vector, keeping its direction. */
export function rubberBand2d(
  dx: number,
  dy: number,
  limit = RUBBER_LIMIT,
): Vec {
  const length = Math.hypot(dx, dy);
  if (length === 0) return { x: 0, y: 0 };
  const scale = rubberBand(length, limit) / length;
  return { x: dx * scale, y: dy * scale };
}

/**
 * One damped-spring step (semi-implicit Euler, substepped for stability).
 * Underdamped defaults give the wobbly overshoot on release.
 */
export function springStep(
  state: Spring,
  target: number,
  dt: number,
  stiffness = 220,
  damping = 9,
): Spring {
  const step = Math.min(dt, 0.064);
  const substeps = Math.max(1, Math.ceil(step / 0.008));
  const h = step / substeps;
  let { x, v } = state;
  for (let i = 0; i < substeps; i += 1) {
    v += (-stiffness * (x - target) - damping * v) * h;
    x += v * h;
  }
  return { x, v };
}

/** Angular velocity (deg/s) for a release velocity (px/s); 0 when too slow for a flick. */
export function flickToOmega(vx: number, vy: number): number {
  const speed = Math.hypot(vx, vy);
  if (speed < FLICK_MIN_SPEED) return 0;
  const direction = vx !== 0 ? Math.sign(vx) : Math.sign(vy);
  return direction * Math.min(speed * 0.9, MAX_OMEGA);
}

/** Add angular velocity, keeping the magnitude bounded. */
export function addSpin(omega: number, delta: number): number {
  const next = omega + delta;
  return Math.sign(next) * Math.min(Math.abs(next), MAX_OMEGA);
}

/** Exponential friction on the spin speed. */
export function frictionStep(
  omega: number,
  dt: number,
  k = SPIN_FRICTION,
): number {
  return omega * Math.exp(-k * dt);
}

/**
 * Advance a spin: free rotation with friction while fast, then a spring that
 * settles on the nearest whole turn so the face ends upright.
 */
export function spinStep(state: Spin, dt: number): Spin {
  if (Math.abs(state.omega) >= 90) {
    const omega = frictionStep(state.omega, dt);
    return { angle: state.angle + omega * dt, omega };
  }
  const target = Math.round(state.angle / 360) * 360;
  const next = springStep(
    { x: state.angle, v: state.omega },
    target,
    dt,
    180,
    14,
  );
  return { angle: next.x, omega: next.v };
}

export function isDizzy(omega: number): boolean {
  return Math.abs(omega) > DIZZY_OMEGA;
}

/** True when the spring and the spin have both come to rest. */
export function isSettled(
  pos: Vec,
  vel: Vec,
  spin: Spin,
  epsilon = 0.05,
): boolean {
  const nearestTurn = Math.round(spin.angle / 360) * 360;
  return (
    Math.abs(pos.x) < epsilon &&
    Math.abs(pos.y) < epsilon &&
    Math.abs(vel.x) < epsilon &&
    Math.abs(vel.y) < epsilon &&
    Math.abs(spin.angle - nearestTurn) < epsilon * 4 &&
    Math.abs(spin.omega) < epsilon * 4
  );
}

/** Unit-or-shorter vector (length <= 1) pointing from the ball towards (dx, dy). */
export function lookDirection(dx: number, dy: number, reach = 160): Vec {
  const length = Math.hypot(dx, dy);
  if (length < 1e-6) return { x: 0, y: 0 };
  const magnitude = Math.min(1, length / reach);
  return { x: (dx / length) * magnitude, y: (dy / length) * magnitude };
}

/** Limit an eye offset to a circle of radius `max`. */
export function clampEyeOffset(x: number, y: number, max = EYE_MAX): Vec {
  const length = Math.hypot(x, y);
  if (length <= max || length === 0) return { x, y };
  return { x: (x / length) * max, y: (y / length) * max };
}

/** Milliseconds until the next idle fidget: 8 to 15 seconds. */
export function nextFidgetDelay(rand: () => number): number {
  return Math.round(8000 + rand() * 7000);
}

const FIDGETS: readonly Fidget[] = ["blink", "look-around", "micro-bounce"];

export function pickFidget(rand: () => number): Fidget {
  const index = Math.min(
    FIDGETS.length - 1,
    Math.floor(rand() * FIDGETS.length),
  );
  return FIDGETS[index];
}
