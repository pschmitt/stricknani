import { describe, expect, it } from "vitest";
import {
  currentRunSeconds,
  elapsedSeconds,
  MAX_TIMER_SECONDS,
  parseProjectTimer,
  secondsFromParts,
  timerParts,
} from "./project-timer";

describe("project timer values", () => {
  it("validates bounded hour, minute, and second inputs", () => {
    expect(secondsFromParts("1", "2", "3")).toBe(3723);
    expect(secondsFromParts("0", "60", "0")).toBeNull();
    expect(secondsFromParts("0", "0", "60")).toBeNull();
    expect(
      secondsFromParts(
        String(Math.floor(MAX_TIMER_SECONDS / 3600) + 1),
        "0",
        "0",
      ),
    ).toBeNull();
    expect(secondsFromParts("-1", "0", "0")).toBeNull();
  });

  it("round trips total time parts", () => {
    expect(timerParts(3_661)).toEqual(["1", "1", "1"]);
    expect(timerParts(MAX_TIMER_SECONDS)).toEqual(["87660", "0", "0"]);
  });

  it("validates the timer envelope contents", () => {
    expect(
      parseProjectTimer({
        total_seconds: 60,
        started_at: "2026-11-05T12:00:00Z",
        server_time: "2026-11-05T12:00:30Z",
      }),
    ).toMatchObject({ total_seconds: 60 });
    expect(() =>
      parseProjectTimer({
        total_seconds: -1,
        started_at: null,
        server_time: "2026-11-05T12:00:30Z",
      }),
    ).toThrow();
  });

  it("adds monotonic time since the server sample only while running", () => {
    const running = {
      total_seconds: 120,
      started_at: "2026-11-05T11:59:00Z",
      server_time: "2026-11-05T12:00:00Z",
    };
    expect(elapsedSeconds(running, 5_500, 0)).toBe(125);
    expect(elapsedSeconds({ ...running, started_at: null }, 5_500, 0)).toBe(
      120,
    );
    expect(currentRunSeconds(running, 5_500, 0)).toBe(65);
    expect(currentRunSeconds({ ...running, started_at: null }, 5_500, 0)).toBe(
      0,
    );
  });
});
