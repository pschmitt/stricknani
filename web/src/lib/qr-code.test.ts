import { describe, expect, it } from "vitest";
import { QR_QUIET_ZONE, qrSvg } from "./qr-code";

describe("qrSvg", () => {
  it("adds a quiet zone around a square module grid", () => {
    const { size } = qrSvg("https://example.com/");
    // Version 1 is 21 modules, so any code is at least that plus the quiet zone.
    expect(size).toBeGreaterThanOrEqual(21 + QR_QUIET_ZONE * 2);
    expect((size - QR_QUIET_ZONE * 2 - 17) % 4).toBe(0);
  });

  it("draws the three finder patterns inside the quiet zone", () => {
    const { size, path } = qrSvg("https://example.com/");
    // The top-left finder starts with a seven module run on the first row.
    expect(path.startsWith(`M${QR_QUIET_ZONE} ${QR_QUIET_ZONE}h7v1h-7z`)).toBe(
      true,
    );
    const far = size - QR_QUIET_ZONE - 7;
    expect(path).toContain(`M${far} ${QR_QUIET_ZONE}h7v1h-7z`);
  });

  it("is deterministic and differs per input", () => {
    expect(qrSvg("a").path).toBe(qrSvg("a").path);
    expect(qrSvg("a").path).not.toBe(qrSvg("b").path);
  });
});
