// Unit checks for the synthetic gzip reverse proxy and the lab INP estimate.
import { gunzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import {
  PerformanceFailure,
  estimateLabInp,
  gzipResponse,
  interactionGroups,
} from "./performance";

const encoder = new TextEncoder();

function clickEntries(durations: number[]) {
  return durations.map((duration, index) => ({
    interaction_id: index + 1,
    duration_ms: duration,
    event_type: "click",
  }));
}

describe("gzip proxy", () => {
  it("compresses an eligible accepted response", () => {
    const body = Buffer.from("synthetic catalog entry ".repeat(120));
    const compressed = gzipResponse("br, gzip", "application/json", body);
    expect(compressed).not.toBeNull();
    expect(gunzipSync(compressed as Buffer).equals(body)).toBe(true);
    expect((compressed as Buffer).length).toBeLessThan(body.length);
  });

  it("compresses the javascript content type", () => {
    const body = Buffer.from(
      "export const fixtureValue = 'synthetic';\n".repeat(100),
    );
    const compressed = gzipResponse(
      "gzip",
      "text/javascript; charset=utf-8",
      body,
    );
    expect(compressed).not.toBeNull();
    expect(gunzipSync(compressed as Buffer).equals(body)).toBe(true);
  });

  it("preserves identity for unaccepted or small responses", () => {
    const big = encoder.encode("x".repeat(2048));
    expect(gzipResponse("identity", "application/json", big)).toBeNull();
    expect(gzipResponse("gzip;q=0", "application/json", big)).toBeNull();
    expect(
      gzipResponse("gzip", "application/json", encoder.encode("small")),
    ).toBeNull();
    expect(gzipResponse("gzip", "image/jpeg", big)).toBeNull();
  });
});

describe("lab INP estimate", () => {
  it("groups entries by interaction and uses the longest event", () => {
    const groups = interactionGroups([
      { interaction_id: 7, duration_ms: 38.2, event_type: "pointerup" },
      { interaction_id: 7, duration_ms: 51.4, event_type: "click" },
      { interaction_id: 14, duration_ms: 22, event_type: "keydown" },
    ]);
    expect(groups).toEqual([
      {
        interaction_id: 7,
        duration_ms: 51.4,
        event_count: 2,
        event_types: new Set(["pointerup", "click"]),
      },
      {
        interaction_id: 14,
        duration_ms: 22,
        event_count: 1,
        event_types: new Set(["keydown"]),
      },
    ]);
  });

  it("uses the interaction count p98 index", () => {
    const entries = clickEntries([190, 150, 110, 70, 40, 20]);
    const candidate49 = estimateLabInp(entries, 49);
    const candidate50 = estimateLabInp(entries, 50);
    const candidate100 = estimateLabInp(entries, 100);
    expect(candidate49.lab_inp_candidate_index).toBe(0);
    expect(candidate49.lab_inp_candidate_ms).toBe(190);
    expect(candidate50.lab_inp_candidate_index).toBe(1);
    expect(candidate50.lab_inp_candidate_ms).toBe(150);
    expect(candidate100.lab_inp_candidate_index).toBe(2);
    expect(candidate100.lab_inp_candidate_ms).toBe(110);
    expect(candidate49.observed_interaction_count).toBe(6);
    expect(candidate49.document_interaction_count).toBe(49);
    expect(candidate49.lab_inp_candidate_assessable).toBe(false);
  });

  it("clamps the p98 index to the available candidates", () => {
    const result = estimateLabInp(clickEntries([200, 80]), 500);
    expect(result.lab_inp_candidate_index).toBe(1);
    expect(result.lab_inp_candidate_ms).toBe(80);
  });

  it("requires representative observation coverage to be assessable", () => {
    const result = estimateLabInp(clickEntries(Array(8).fill(24)), 16);
    expect(result.observed_interaction_coverage).toBe(0.5);
    expect(result.lab_inp_candidate_assessable).toBe(true);
  });

  it("considers only the ten slowest candidates", () => {
    const durations = Array.from({ length: 20 }, (_, index) => 219 - index);
    const result = estimateLabInp(clickEntries(durations), 600);
    expect(result.lab_inp_candidate_index).toBe(9);
    expect(result.lab_inp_candidate_ms).toBe(210);
    expect(result.observed_interaction_count).toBe(20);
    expect(result.lab_inp_candidate_assessable).toBe(false);
  });

  it("rejects missing or invalid observations instead of zero", () => {
    const entry = { interaction_id: 1, duration_ms: 30, event_type: "click" };
    const cases: Array<[unknown, unknown]> = [
      [[], 0],
      [[entry], null],
      [[entry], 0],
    ];
    for (const [entries, count] of cases) {
      expect(() => estimateLabInp(entries, count)).toThrow(PerformanceFailure);
    }
    expect(() =>
      interactionGroups([
        { interaction_id: 1, duration_ms: 15, event_type: "click" },
      ]),
    ).toThrow(PerformanceFailure);
  });
});
