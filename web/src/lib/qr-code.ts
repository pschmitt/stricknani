import qrcode from "qrcode-generator";

/** Quiet zone in modules; the QR specification asks for four. */
export const QR_QUIET_ZONE = 4;

export interface QrSvg {
  /** Side length of the square viewBox, including the quiet zone. */
  size: number;
  /** One path drawing every dark module as horizontal runs. */
  path: string;
}

/**
 * Encodes `text` as a QR code and returns SVG geometry (one unit per module).
 * Medium error correction keeps the code small enough to scan from a monitor.
 */
export function qrSvg(text: string): QrSvg {
  const code = qrcode(0, "M");
  code.addData(text);
  code.make();
  const count = code.getModuleCount();
  const commands: string[] = [];
  for (let row = 0; row < count; row += 1) {
    let col = 0;
    while (col < count) {
      if (!code.isDark(row, col)) {
        col += 1;
        continue;
      }
      const start = col;
      while (col < count && code.isDark(row, col)) col += 1;
      commands.push(
        `M${start + QR_QUIET_ZONE} ${row + QR_QUIET_ZONE}h${col - start}v1h-${col - start}z`,
      );
    }
  }
  return { size: count + QR_QUIET_ZONE * 2, path: commands.join("") };
}
