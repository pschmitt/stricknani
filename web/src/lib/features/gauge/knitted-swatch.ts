/** The most stitches/rows drawn; denser gauges simply look "very dense". */
export const MAX_DRAWN_STITCHES = 32;
export const MAX_DRAWN_ROWS = 40;

export interface SwatchPath {
  /** Stitches of even rows (0, 2, ...) and odd rows, drawn in two shades like real knitting. */
  even: string;
  odd: string;
  strokeWidth: number;
}

function count(value: number | string | undefined, max: number): number {
  const parsed = Number.parseInt(String(value ?? ""), 10);
  return Number.isFinite(parsed) && parsed > 0 ? Math.min(parsed, max) : 0;
}

/**
 * SVG path data (100 × 100 viewBox) for a stockinette swatch with one "V" per stitch, or null
 * when either count is missing.
 */
export function swatchPath(
  stitches: number | string | undefined,
  rows: number | string | undefined,
): SwatchPath | null {
  const columns = count(stitches, MAX_DRAWN_STITCHES);
  const lines = count(rows, MAX_DRAWN_ROWS);
  if (columns === 0 || lines === 0) return null;
  const width = 100 / columns;
  const height = 100 / lines;
  const round = (n: number) => Math.round(n * 100) / 100;
  const even: string[] = [];
  const odd: string[] = [];
  for (let row = 0; row < lines; row += 1) {
    const top = row * height;
    for (let column = 0; column < columns; column += 1) {
      const left = column * width;
      const v =
        `M${round(left + width * 0.12)} ${round(top + height * 0.1)}` +
        `L${round(left + width / 2)} ${round(top + height * 0.95)}` +
        `L${round(left + width * 0.88)} ${round(top + height * 0.1)}`;
      (row % 2 === 0 ? even : odd).push(v);
    }
  }
  return {
    even: even.join(""),
    odd: odd.join(""),
    strokeWidth: round(
      Math.min(Math.max(Math.min(width, height) * 0.28, 0.6), 4),
    ),
  };
}
