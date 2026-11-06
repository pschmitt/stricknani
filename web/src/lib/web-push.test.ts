import { describe, expect, it } from "vitest";
import { base64UrlToBytes, timerReminderSchedule } from "./web-push.ts";

describe("base64UrlToBytes", () => {
  it("decodes unpadded base64url", () => {
    expect([...base64UrlToBytes("AQID_-8")]).toEqual([1, 2, 3, 255, 239]);
    expect(base64UrlToBytes("").length).toBe(0);
  });
});

describe("timerReminderSchedule", () => {
  const started = "2026-11-06T10:00:00Z";

  it("is due one interval into the run, or at the snoozed point", () => {
    expect(timerReminderSchedule(started, 30, null, "de")).toEqual({
      started_at: started,
      interval_minutes: 30,
      next_at: "2026-11-06T10:30:00.000Z",
      locale: "de",
    });
    // "Keep timing" after 50 minutes with a one-hour interval.
    expect(
      timerReminderSchedule(started, 60, 50 * 60 + 3600, "en")?.next_at,
    ).toBe("2026-11-06T11:50:00.000Z");
  });

  it("has no schedule without a run or with reminders off", () => {
    expect(timerReminderSchedule(null, 60, null, "en")).toBeNull();
    expect(timerReminderSchedule(started, 0, null, "en")).toBeNull();
    expect(timerReminderSchedule(started, 45, null, "en")).toBeNull();
    expect(timerReminderSchedule("not a date", 60, null, "en")).toBeNull();
  });

  it("only sends a supported locale", () => {
    expect(timerReminderSchedule(started, 60, null, "fr")?.locale).toBe("en");
  });
});
