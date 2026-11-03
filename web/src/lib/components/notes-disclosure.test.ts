import { describe, expect, it } from "vitest";
import {
  CARD_TILT,
  FAB_TILT,
  SWAY_KEYFRAMES,
  createNotesDisclosure,
  flipFrom,
  type NotesDirection,
  type NotesPhase,
} from "./notes-disclosure";

function harness(options: { restoreFocus?: boolean } = {}) {
  const log: string[] = [];
  const pending: Array<{
    direction: NotesDirection;
    resolve: (finished: boolean) => void;
  }> = [];
  const disclosure = createNotesDisclosure({
    run: (direction) =>
      new Promise((resolve) => {
        pending.push({ direction, resolve });
      }),
    onPhase: (phase: NotesPhase) => log.push(`phase:${phase}`),
    focusCard: () => log.push("focus:card"),
    focusFab: () => log.push("focus:fab"),
    shouldRestoreFocus: () => options.restoreFocus ?? true,
  });
  const finish = async (finished = true) => {
    pending.shift()?.resolve(finished);
    await Promise.resolve();
    await Promise.resolve();
  };
  return { disclosure, log, pending, finish };
}

describe("createNotesDisclosure", () => {
  it("opens: moves focus into the card, then settles open", async () => {
    const { disclosure, log, finish } = harness();
    disclosure.open();
    expect(disclosure.phase).toBe("opening");
    expect(log).toEqual(["phase:opening", "focus:card"]);
    await finish();
    expect(disclosure.phase).toBe("open");
  });

  it("closes: returns focus to the FAB once the morph has finished", async () => {
    const { disclosure, log, finish } = harness();
    disclosure.open();
    await finish();
    log.length = 0;
    disclosure.close();
    expect(disclosure.phase).toBe("closing");
    expect(log).toEqual(["phase:closing"]);
    await finish();
    expect(disclosure.phase).toBe("closed");
    expect(log).toEqual(["phase:closing", "phase:closed", "focus:fab"]);
  });

  it("leaves focus alone when the person has moved on", async () => {
    const { disclosure, log, finish } = harness({ restoreFocus: false });
    disclosure.open();
    await finish();
    disclosure.close();
    await finish();
    expect(log).not.toContain("focus:fab");
  });

  it("toggle follows the current state", async () => {
    const { disclosure, finish } = harness();
    disclosure.toggle();
    expect(disclosure.phase).toBe("opening");
    await finish();
    disclosure.toggle();
    expect(disclosure.phase).toBe("closing");
  });

  it("closes on Escape only while open and ignores other keys", async () => {
    const { disclosure, finish } = harness();
    expect(disclosure.handleKey("Escape")).toBe(false);
    disclosure.open();
    await finish();
    expect(disclosure.handleKey("Enter")).toBe(false);
    expect(disclosure.phase).toBe("open");
    expect(disclosure.handleKey("Escape")).toBe(true);
    expect(disclosure.phase).toBe("closing");
  });

  it("can be reversed mid-morph without ending in the wrong state", async () => {
    const { disclosure, pending, finish } = harness();
    disclosure.open();
    disclosure.close();
    expect(pending.map((entry) => entry.direction)).toEqual(["open", "close"]);
    // The interrupted open reports false and must not mark the note open.
    pending[0].resolve(false);
    await Promise.resolve();
    await Promise.resolve();
    expect(disclosure.phase).toBe("closing");
    pending.shift();
    await finish();
    expect(disclosure.phase).toBe("closed");
  });

  it("ignores repeated open and close requests", () => {
    const { disclosure, pending } = harness();
    disclosure.close();
    expect(pending).toHaveLength(0);
    disclosure.open();
    disclosure.open();
    expect(pending).toHaveLength(1);
  });
});

describe("flipFrom", () => {
  it("places the card start pose over the FAB, pivoting at the pin", () => {
    const flip = flipFrom(
      { left: 1000, top: 600, width: 56 },
      { left: 800, top: 300, width: 360, height: 280 },
      56,
    );
    // Top centres line up: FAB centre 1028, card centre 980.
    expect(flip.dx).toBe(48);
    expect(flip.dy).toBe(300);
    expect(flip.sx).toBeCloseTo(56 / 360);
    expect(flip.sy).toBeCloseTo(56 / 280);
  });
});

describe("motion constants", () => {
  it("tilts from the FAB pose to the card pose", () => {
    expect(FAB_TILT).toBe(4);
    expect(CARD_TILT).toBe(-1.5);
  });

  it("sways with a decaying amplitude that ends at rest", () => {
    const deltas = SWAY_KEYFRAMES.map((frame) => frame.delta);
    expect(deltas[0]).toBe(0);
    expect(deltas[deltas.length - 1]).toBe(0);
    const peaks = deltas.slice(1, -1).map(Math.abs);
    expect([...peaks].sort((a, b) => b - a)).toEqual(peaks);
  });
});
