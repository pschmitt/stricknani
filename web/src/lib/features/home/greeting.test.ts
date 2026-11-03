import { beforeEach, describe, expect, it } from "vitest";
import {
  GREETING_POOL_SIZE,
  GREETINGS,
  greetingFor,
  greetingSlot,
  pickGreetingIndex,
  resetVisitGreeting,
  visitGreetingIndex,
} from "./greeting";

describe("greeting slots", () => {
  it("maps every hour to its slot", () => {
    const expected = (hour: number) =>
      hour >= 5 && hour <= 11
        ? "morning"
        : hour >= 12 && hour <= 17
          ? "day"
          : hour >= 18 && hour <= 22
            ? "evening"
            : "night";
    for (let hour = 0; hour < 24; hour += 1) {
      expect(greetingSlot(hour)).toBe(expected(hour));
    }
  });

  it("keeps exactly five distinct greetings per slot", () => {
    for (const pool of Object.values(GREETINGS)) {
      expect(pool).toHaveLength(GREETING_POOL_SIZE);
      expect(new Set(pool).size).toBe(GREETING_POOL_SIZE);
    }
  });

  it("picks the greeting from the slot pool", () => {
    expect(greetingFor(8, 1)).toBe("Morning, sunshine!");
    expect(greetingFor(13, 4)).toBe("Well, hi!");
    expect(greetingFor(20, 0)).toBe("Good evening!");
    expect(greetingFor(2, 3)).toBe("Just one more row?");
  });
});

describe("greeting pick", () => {
  beforeEach(resetVisitGreeting);

  it("stays inside the pool for any random value", () => {
    expect(pickGreetingIndex(() => 0)).toBe(0);
    expect(pickGreetingIndex(() => 0.999999)).toBe(GREETING_POOL_SIZE - 1);
    expect(pickGreetingIndex(() => 1)).toBe(GREETING_POOL_SIZE - 1);
  });

  it("is stable for the whole visit", () => {
    const first = visitGreetingIndex();
    for (let call = 0; call < 50; call += 1) {
      expect(visitGreetingIndex()).toBe(first);
    }
  });
});
