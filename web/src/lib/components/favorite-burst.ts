export type BurstParticle = {
  /** Horizontal travel in px, from the heart's centre. */
  dx: number;
  /** Vertical travel in px (negative is up). */
  dy: number;
  /** Particle size in px. */
  size: number;
  /** Start delay in ms, so the burst does not fire as one ring. */
  delay: number;
};

/**
 * Evenly spaced particles for the favourite burst. Every second particle
 * travels further and is smaller, which reads as confetti and not as a ring.
 * The Android client uses the same geometry (FavoriteBurst.kt).
 */
export function burstParticles(
  count = 8,
  nearRadius = 24,
  farRadius = 34,
): BurstParticle[] {
  return Array.from({ length: count }, (_, index) => {
    const angle = -Math.PI / 2 + (index * 2 * Math.PI) / count;
    const far = index % 2 === 1;
    const radius = far ? farRadius : nearRadius;
    return {
      dx: Math.round(Math.cos(angle) * radius * 100) / 100,
      dy: Math.round(Math.sin(angle) * radius * 100) / 100,
      size: far ? 6 : 8,
      delay: far ? 40 : 0,
    };
  });
}
