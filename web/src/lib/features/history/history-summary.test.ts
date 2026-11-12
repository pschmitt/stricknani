import { describe, expect, it } from "vitest";
import type { ChangeSetV1 } from "#lib/features/admin/admin-api.ts";
import {
  actionLabel,
  actionTone,
  eventHeadline,
  formatTimerDuration,
  impersonatedByText,
  impersonationHeadline,
  relativeTime,
  sharingSentence,
  summarizeChanges,
  timerHeadline,
  timerSeconds,
} from "./history-summary";

const text = (field: string, before: string | null, after: string | null) => ({
  field,
  before: { type: "text" as const, value: before },
  after: { type: "text" as const, value: after },
});
const count = (field: string, before: number, after: number) => ({
  field,
  before: { type: "integer" as const, value: before },
  after: { type: "integer" as const, value: after },
});
const set = (...items: ChangeSetV1["items"]): ChangeSetV1 => ({
  version: 1,
  items,
});

describe("summarizeChanges", () => {
  it("names the changed fields", () => {
    expect(
      summarizeChanges(
        set(text("project.name", "a", "b"), text("project.notes", "", "x")),
        "en",
      ),
    ).toBe("Changed name and notes");
  });

  it("collapses long field lists", () => {
    expect(
      summarizeChanges(
        set(
          text("project.name", "a", "b"),
          text("project.notes", "", "x"),
          text("project.needles", "", "4"),
          text("project.description", "", "d"),
          text("project.link", null, "l"),
        ),
        "en",
      ),
    ).toBe("Changed name, notes, and 3 more");
  });

  it("describes photo and step counts", () => {
    expect(
      summarizeChanges(set(count("project.image_count", 1, 3)), "en"),
    ).toBe("Added 2 photos");
    expect(summarizeChanges(set(count("yarn.photo_count", 2, 1)), "en")).toBe(
      "Removed a photo",
    );
    expect(summarizeChanges(set(count("project.step_count", 4, 3)), "en")).toBe(
      "Removed a step",
    );
  });

  it("combines field names and counts, without double-counting steps", () => {
    const steps = {
      field: "project.steps",
      before: { type: "steps" as const, value: [] },
      after: {
        type: "steps" as const,
        value: [{ number: 1, title: "Cast on", description: null }],
      },
    };
    expect(
      summarizeChanges(
        set(
          text("project.notes", "", "x"),
          count("project.step_count", 0, 1),
          steps,
        ),
        "en",
      ),
    ).toBe("Changed notes · Added a step");
  });

  it("returns null when nothing is summarisable", () => {
    expect(summarizeChanges(null, "en")).toBeNull();
    expect(summarizeChanges(set(), "en")).toBeNull();
    expect(
      summarizeChanges(set(count("project.image_count", 2, 2)), "en"),
    ).toBeNull();
  });
});

describe("eventHeadline", () => {
  it("falls back to the action label", () => {
    expect(eventHeadline("created", null, "en")).toBe("Created");
    expect(eventHeadline("something_new", null, "en")).toBe("something_new");
  });
});

describe("actionTone", () => {
  it("uses the audit log colour families", () => {
    expect(actionTone("created")).toBe("create");
    expect(actionTone("updated")).toBe("update");
    expect(actionTone("photo_deleted")).toBe("delete");
    expect(actionTone("removed")).toBe("delete");
    expect(actionTone("photo_uploaded")).toBe("upload");
    expect(actionTone("mystery")).toBe("other");
  });
});

describe("relativeTime", () => {
  const now = new Date("2026-05-01T12:00:00Z");
  it("formats recent and older times", () => {
    expect(relativeTime("2026-05-01T10:00:00Z", now, "en")).toBe("2 hours ago");
    expect(relativeTime("2026-04-30T12:00:00Z", now, "en")).toBe("yesterday");
    expect(relativeTime("2026-05-01T11:59:50Z", now, "en")).toBe("now");
    expect(relativeTime("2026-05-01T10:00:00Z", now, "de")).toBe(
      "vor 2 Stunden",
    );
  });
  it("passes through unparsable dates", () => {
    expect(relativeTime("nope", now, "en")).toBe("nope");
  });
});

describe("sharing events", () => {
  it("names the person and the role, as Android does", () => {
    const anika = { id: 7, name: "Anika", role: "editor" };
    expect(eventHeadline("ownership_transferred", null, "en", anika)).toBe(
      "Transferred to Anika",
    );
    expect(eventHeadline("shared", null, "en", anika)).toBe(
      "Shared with Anika · Can edit",
    );
    expect(
      eventHeadline("share_role_changed", null, "en", {
        ...anika,
        role: "viewer",
      }),
    ).toBe("Role of Anika changed to Can view");
    expect(eventHeadline("share_revoked", null, "en", anika)).toBe(
      "Stopped sharing with Anika",
    );
    expect(eventHeadline("share_left", null, "en", anika)).toBe("Anika left");
  });

  it("splits the sentence so the person renders as a UserLabel", () => {
    const anika = { id: 7, name: "Anika", role: "editor", avatar_url: "/a/7" };
    expect(sharingSentence("shared", anika, "en")).toEqual({
      template: "Shared with {name} · Can edit",
      users: { name: { kind: "user", name: "Anika", avatarUrl: "/a/7" } },
    });
    expect(sharingSentence("share_left", null, "en")?.users.name.name).toBe(
      "a deleted account",
    );
    expect(sharingSentence("updated", anika, "en")).toBeNull();
  });

  it("says when the account is gone and never shows the raw action", () => {
    expect(
      eventHeadline("ownership_transferred", null, "en", {
        id: null,
        name: null,
        role: null,
      }),
    ).toBe("Transferred to a deleted account");
    expect(actionLabel("ownership_transferred", "en")).toBe(
      "Ownership transferred",
    );
  });
});

describe("timer events", () => {
  const seconds = (before: number, after: number) =>
    set(count("project.timer_seconds", before, after));

  it("formats a length of knitting time like Android", () => {
    expect(formatTimerDuration(0)).toBe("< 1 min");
    expect(formatTimerDuration(59)).toBe("< 1 min");
    expect(formatTimerDuration(25 * 60 + 30)).toBe("25 min");
    expect(formatTimerDuration(3600)).toBe("1 h");
    expect(formatTimerDuration(3 * 3600 + 10 * 60)).toBe("3 h 10 min");
    expect(formatTimerDuration(-5)).toBe("< 1 min");
  });

  // The German wording lives in the PO catalogue (checked by i18n:check), which is loaded at
  // runtime and not in these tests.
  it("words start, stop and edit with the length of time", () => {
    expect(eventHeadline("timer_started", null, "en")).toBe("Timer started");
    expect(eventHeadline("timer_stopped", seconds(600, 2100), "en")).toBe(
      "Timer stopped · 25 min",
    );
    expect(
      eventHeadline("timer_total_changed", seconds(600, 11400), "en"),
    ).toBe("Knitting time changed to 3 h 10 min");
  });

  it("falls back to the plain action before the details are loaded", () => {
    expect(eventHeadline("timer_stopped", null, "en")).toBe("Timer stopped");
    expect(eventHeadline("timer_total_changed", null, "en")).toBe(
      "Knitting time changed",
    );
    expect(actionLabel("timer_started", "en")).toBe("Timer started");
    expect(timerSeconds(null)).toBeNull();
    expect(timerHeadline("created", null, "en")).toBeNull();
  });
});

describe("impersonation wording", () => {
  it("names the administrator in the headline of both events", () => {
    expect(impersonationHeadline("impersonation_started", "Ann", "en")).toBe(
      "Impersonation by Ann started",
    );
    expect(impersonationHeadline("impersonation_stopped", "Ann", "en")).toBe(
      "Impersonation by Ann stopped",
    );
    expect(impersonationHeadline("updated", "Ann", "en")).toBeNull();
  });

  it("says who acted as whom", () => {
    expect(impersonatedByText("Ann", "bea@example.test", "en")).toBe(
      "by Ann as bea@example.test",
    );
  });
});
