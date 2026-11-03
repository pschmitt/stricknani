export const DEFAULT_SWATCH_CM = 10;
export const MAX_SWATCH_CM = 100;

export interface SwatchSize {
  width: number;
  height: number;
}

export const defaultSwatchSize = (): SwatchSize => ({
  width: DEFAULT_SWATCH_CM,
  height: DEFAULT_SWATCH_CM,
});

/** A centimetre value with 0 < x <= 100; accepts a decimal comma. */
export function parseCm(text: string): number | null {
  const trimmed = text.trim().replace(",", ".");
  if (!/^\d*\.?\d+$|^\d+\.$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) && value > 0 && value <= MAX_SWATCH_CM
    ? value
    : null;
}

export function formatCm(value: number): string {
  return String(Math.round(value * 100) / 100);
}

export function isDefaultSize(size: SwatchSize): boolean {
  return size.width === DEFAULT_SWATCH_CM && size.height === DEFAULT_SWATCH_CM;
}

export function sizeLabel(size: SwatchSize): string {
  return `${formatCm(size.width)} × ${formatCm(size.height)} cm`;
}

/** Fraction of the swatch frame's box the drawing fills: the longer side is 1. */
export function swatchAspect(size: SwatchSize): { w: number; h: number } {
  const longest = Math.max(size.width, size.height);
  return { w: size.width / longest, h: size.height / longest };
}
