import { afterEach, describe, expect, it, vi } from "vitest";
import {
  activityAgo,
  activityDayKey,
  activityDayLabel,
  activityIcon,
  activityTime,
  groupActivity,
  mergeActivityRuns,
  activityDetail,
  activityHeadline,
  activityItemActor,
  activityOwner,
  activityResourceName,
  activityStateNote,
  loadActivity,
  mergeActivity,
  parseActivityItem,
  parseActivityPage,
} from "./activity";

const raw = {
  id: 412,
  first_event_id: 409,
  created_at: "2026-11-03T10:09:12.000000Z",
  first_created_at: "2026-11-03T10:00:03.000000Z",
  action: "updated",
  entity_type: "project",
  entity_id: 17,
  entity_name: "Winter socks",
  exists: true,
  href: "/app/projects/17",
  thumbnail_url: "/media/thumbnails/projects/17/thumb_cover.jpg",
  actor: { kind: "self", name: "Philipp", is_self: true, avatar_url: null },
  changed_fields: ["project.name", "project.needles"],
  count: 4,
};

afterEach(() => vi.unstubAllGlobals());

describe("activity parsing", () => {
  it("parses an item", () => {
    const item = parseActivityItem(raw);
    expect(item.count).toBe(4);
    expect(item.href).toBe("/app/projects/17");
    expect(item.actor.is_self).toBe(true);
  });

  it("drops links and thumbnails for deleted or foreign targets", () => {
    const gone = parseActivityItem({
      ...raw,
      exists: false,
      href: "/app/projects/17",
      thumbnail_url: "https://evil.example/x.png",
    });
    expect(gone.href).toBeNull();
    expect(gone.thumbnail_url).toBeNull();
    expect(
      parseActivityItem({ ...raw, href: "//evil.example" }).href,
    ).toBeNull();
  });

  it("tolerates a missing name and unknown fields", () => {
    const item = parseActivityItem({
      ...raw,
      entity_name: null,
      changed_fields: ["project.name", 3],
    });
    expect(item.entity_name).toBeNull();
    expect(item.changed_fields).toEqual(["project.name"]);
  });

  it("rejects malformed items and pages", () => {
    expect(() => parseActivityItem({ ...raw, id: "x" })).toThrow();
    expect(() =>
      parseActivityItem({ ...raw, entity_type: "category" }),
    ).toThrow();
    expect(() => parseActivityPage({ data: {} })).toThrow();
    expect(() => parseActivityPage({ data: [], meta: {} })).toThrow();
  });

  it("only offers more when there is a cursor", () => {
    const page = parseActivityPage({
      data: [raw],
      meta: { limit: 6, has_more: true, next_before: 409 },
    });
    expect(page.has_more).toBe(true);
    expect(page.next_before).toBe(409);
    expect(
      parseActivityPage({
        data: [],
        meta: { limit: 6, has_more: true, next_before: null },
      }).has_more,
    ).toBe(false);
  });

  it("merges pages without duplicates", () => {
    const a = parseActivityItem(raw);
    const b = parseActivityItem({ ...raw, id: 300 });
    expect(mergeActivity([a], [a, b]).map((i) => i.id)).toEqual([412, 300]);
  });
});

describe("loadActivity", () => {
  it("requests the limit and the cursor", async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL) =>
      Promise.resolve(
        Response.json({
          data: [raw],
          meta: { limit: 6, has_more: false, next_before: null },
        }),
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    const page = await loadActivity(new AbortController().signal, 409);
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      "/api/v2/activity?limit=6&before=409",
    );
    expect(page.items).toHaveLength(1);
  });
});

describe("activity text", () => {
  it("words the action like the project history", () => {
    expect(activityHeadline(parseActivityItem(raw), "en")).toBe("Updated");
    for (const [action, text] of [
      ["timer_started", "Timer started"],
      ["timer_stopped", "Timer stopped"],
      ["timer_total_changed", "Knitting time changed"],
      ["restored", "Restored from trash"],
      ["trashed", "Moved to trash"],
      ["photo_promoted", "Cover photo changed"],
    ] as const)
      expect(
        activityHeadline(parseActivityItem({ ...raw, action }), "en"),
      ).toBe(text);
  });

  it("words sharing events with the other person", () => {
    const item = parseActivityItem({
      ...raw,
      action: "shared",
      subject: { id: 2, name: "Anna", role: "editor" },
    });
    expect(activityHeadline(item, "en")).toBe("Shared with Anna · Can edit");
  });

  it("always names the actor, as You, a name, an administrator, System or Unknown", () => {
    const actors = [
      [{ kind: "self", name: "Philipp", is_self: true }, "self", "Philipp"],
      [
        { kind: "user", name: "Anna Berg", is_self: false },
        "user",
        "Anna Berg",
      ],
      [{ kind: "admin", name: null, is_self: false }, "admin", null],
      [{ kind: "system", name: null, is_self: false }, "system", null],
      [{ kind: "unknown", name: null, is_self: false }, "unknown", null],
    ] as const;
    for (const [actor, kind, name] of actors) {
      const item = parseActivityItem({
        ...raw,
        actor: { ...actor, avatar_url: null },
      });
      expect(activityItemActor(item)).toMatchObject({ kind, name });
    }
  });

  it("reads the actor of an older server from is_self and name", () => {
    const old = (actor: object, extra = {}) =>
      activityItemActor(parseActivityItem({ ...raw, actor, ...extra }));
    expect(old({ name: "philipp", is_self: true }).kind).toBe("self");
    expect(old({ name: "Anna", is_self: false }).kind).toBe("user");
    expect(old({ name: null, is_self: false }).kind).toBe("admin");
    expect(old({ name: null, is_self: false }, { system: true }).kind).toBe(
      "system",
    );
  });

  it("uses a generic noun when the name is unknown", () => {
    const item = parseActivityItem({
      ...raw,
      entity_name: null,
      entity_type: "yarn",
      action: "deleted",
    });
    expect(activityResourceName(item, "en")).toBe("a yarn");
    expect(activityResourceName(parseActivityItem(raw), "en")).toBe(
      "Winter socks",
    );
  });

  it("lists the change count and field labels", () => {
    expect(activityDetail(parseActivityItem(raw), "en")).toBe(
      "4 changes · name, needles",
    );
    expect(
      activityDetail(
        parseActivityItem({ ...raw, count: 1, changed_fields: [] }),
        "en",
      ),
    ).toBe("");
  });

  it("collapses long field lists and merges photo counters", () => {
    const item = parseActivityItem({
      ...raw,
      count: 1,
      changed_fields: [
        "project.name",
        "project.notes",
        "project.tags",
        "project.steps",
        "project.image_count",
        "project.unknown",
      ],
    });
    expect(activityDetail(item, "en")).toBe("name, notes, 3 more");
  });
});

describe("activityAgo", () => {
  const now = new Date(2026, 10, 3, 12, 0, 0);
  const at = (d: Date) => d.toISOString();

  it("is relative within a day", () => {
    expect(activityAgo(at(new Date(2026, 10, 3, 11, 59, 40)), "en", now)).toBe(
      "now",
    );
    expect(activityAgo(at(new Date(2026, 10, 3, 11, 55)), "en", now)).toBe(
      "5 minutes ago",
    );
    expect(activityAgo(at(new Date(2026, 10, 3, 10, 0)), "en", now)).toBe(
      "2 hours ago",
    );
  });

  it("switches to days and weeks", () => {
    expect(activityAgo(at(new Date(2026, 10, 2, 23, 0)), "en", now)).toBe(
      "yesterday",
    );
    expect(activityAgo(at(new Date(2026, 9, 31, 9, 0)), "en", now)).toBe(
      "3 days ago",
    );
    expect(activityAgo(at(new Date(2026, 9, 10, 9, 0)), "en", now)).toBe(
      "3 weeks ago",
    );
  });

  it("returns nothing for an invalid date", () => {
    expect(activityAgo("nope", "en", now)).toBe("");
  });
});

describe("activityIcon", () => {
  it("maps the audit vocabulary to node icons", () => {
    expect(activityIcon("created")).toBe("sparkle");
    expect(activityIcon("updated")).toBe("action-update");
    expect(activityIcon("uploaded")).toBe("camera");
    expect(activityIcon("photo_uploaded")).toBe("camera");
    expect(activityIcon("attachment_uploaded")).toBe("action-upload");
    expect(activityIcon("deleted")).toBe("action-delete");
    expect(activityIcon("removed")).toBe("action-remove");
    expect(activityIcon("timer_started")).toBe("stopwatch");
    expect(activityIcon("timer_total_changed")).toBe("stopwatch");
    expect(activityIcon("shared")).toBe("share");
    expect(activityIcon("ownership_transferred")).toBe("share");
  });

  it("reads unknown actions by suffix and falls back", () => {
    expect(activityIcon("pattern_created")).toBe("sparkle");
    expect(activityIcon("pattern_deleted")).toBe("action-delete");
    expect(activityIcon("Step_Updated")).toBe("action-update");
    expect(activityIcon("whatever")).toBe("action-default");
    expect(activityIcon("constructor")).toBe("action-default");
  });
});

describe("day grouping", () => {
  const now = new Date(2026, 10, 3, 12, 0, 0);
  const iso = (y: number, m: number, d: number, h = 12, min = 0) =>
    new Date(y, m, d, h, min).toISOString();

  it("keys by local calendar day", () => {
    expect(activityDayKey(iso(2026, 10, 3, 0, 5))).toBe("2026-11-03");
    expect(activityDayKey(iso(2026, 10, 3, 23, 55))).toBe("2026-11-03");
    expect(activityDayKey("nope")).toBe("unknown");
  });

  it("labels today, yesterday and dates", () => {
    expect(activityDayLabel(iso(2026, 10, 3, 0, 1), "en", now)).toBe("Today");
    expect(activityDayLabel(iso(2026, 10, 2, 23, 59), "en", now)).toBe(
      "Yesterday",
    );
    expect(activityDayLabel(iso(2026, 1, 14), "en", now)).toBe("Feb 14");
    expect(activityDayLabel(iso(2025, 1, 14), "en", now)).toBe("Feb 14, 2025");
    expect(activityDayLabel(iso(2026, 1, 14), "de", now)).toContain("14.");
    expect(activityDayLabel("nope", "en", now)).toBe("");
  });

  it("formats the time of day as 24 hours", () => {
    expect(activityTime(iso(2026, 10, 3, 14, 32), "en")).toBe("14:32");
    expect(activityTime(iso(2026, 10, 3, 9, 5), "de")).toBe("09:05");
    expect(activityTime("nope", "en")).toBe("");
  });

  it("splits a feed into days, newest first", () => {
    const items = [
      parseActivityItem({ ...raw, id: 5, created_at: iso(2026, 10, 3, 11) }),
      parseActivityItem({
        ...raw,
        id: 4,
        entity_id: 9,
        created_at: iso(2026, 10, 3, 9),
      }),
      parseActivityItem({ ...raw, id: 3, created_at: iso(2026, 10, 2, 20) }),
      parseActivityItem({ ...raw, id: 2, created_at: iso(2026, 1, 14, 8) }),
    ];
    const days = groupActivity(items, "en", now);
    expect(days.map((d) => d.label)).toEqual(["Today", "Yesterday", "Feb 14"]);
    expect(days.map((d) => d.items.length)).toEqual([2, 1, 1]);
  });
});

describe("mergeActivityRuns", () => {
  const entry = (over: Record<string, unknown>) =>
    parseActivityItem({
      ...raw,
      count: 1,
      created_at: "2026-11-03T10:00:00.000Z",
      first_created_at: "2026-11-03T10:00:00.000Z",
      ...over,
    });

  it("folds consecutive runs and unites the fields", () => {
    const merged = mergeActivityRuns([
      entry({
        id: 30,
        first_event_id: 28,
        count: 2,
        changed_fields: ["project.notes"],
      }),
      entry({
        id: 20,
        first_event_id: 20,
        first_created_at: "2026-11-03T07:00:00.000Z",
        changed_fields: ["project.notes", "project.needles"],
      }),
    ]);
    expect(merged).toHaveLength(1);
    expect(merged[0]?.id).toBe(30);
    expect(merged[0]?.count).toBe(3);
    expect(merged[0]?.first_event_id).toBe(20);
    expect(merged[0]?.first_created_at).toBe("2026-11-03T07:00:00.000Z");
    expect(merged[0]?.changed_fields).toEqual([
      "project.notes",
      "project.needles",
    ]);
  });

  it("keeps different items, actions and actors apart", () => {
    const base = { changed_fields: [] };
    expect(
      mergeActivityRuns([
        entry({ ...base, id: 3 }),
        entry({ ...base, id: 2, entity_id: 99 }),
        entry({ ...base, id: 1, action: "uploaded" }),
      ]),
    ).toHaveLength(3);
    expect(
      mergeActivityRuns([
        entry({ id: 3 }),
        entry({
          id: 2,
          actor: {
            kind: "admin",
            name: null,
            is_self: false,
            avatar_url: null,
          },
        }),
      ]),
    ).toHaveLength(2);
  });

  it("never folds timer or sharing events", () => {
    for (const action of ["timer_started", "shared"]) {
      const a = parseActivityItem({ ...raw, action, id: 2, first_event_id: 2 });
      const b = parseActivityItem({ ...raw, action, id: 1, first_event_id: 1 });
      expect(mergeActivityRuns([a, b])).toHaveLength(2);
    }
  });

  it("only merges neighbours, never created or deleted", () => {
    expect(
      mergeActivityRuns([
        entry({ id: 4 }),
        entry({ id: 3, entity_id: 99 }),
        entry({ id: 2 }),
      ]),
    ).toHaveLength(3);
    expect(
      mergeActivityRuns([
        entry({ id: 2, action: "deleted" }),
        entry({ id: 1, action: "deleted" }),
      ]),
    ).toHaveLength(2);
  });

  it("does not merge across days", () => {
    const days = groupActivity(
      [
        entry({ id: 2, created_at: new Date(2026, 10, 3, 9).toISOString() }),
        entry({ id: 1, created_at: new Date(2026, 10, 2, 9).toISOString() }),
      ],
      "en",
      new Date(2026, 10, 3, 12),
    );
    expect(days).toHaveLength(2);
  });
});

describe("trash activity", () => {
  const trashed = {
    ...raw,
    action: "trashed",
    exists: false,
    count: 1,
    state: "in_trash",
    trash: {
      deleted_at: "2026-11-03T10:00:00Z",
      purge_at: "2027-02-01T10:00:00Z",
      days_remaining: 89,
      can_restore: true,
    },
  };

  it("keeps old items valid without the new fields", () => {
    const item = parseActivityItem(raw);
    expect(item).toMatchObject({
      state: null,
      trash: null,
      system: false,
      subject: null,
    });
  });

  it("does not link an item that sits in the trash", () => {
    const item = parseActivityItem({ ...trashed, exists: true });
    expect(item.state).toBe("in_trash");
    expect(item.href).toBeNull();
    expect(item.trash?.can_restore).toBe(true);
    expect(() => parseActivityItem({ ...raw, state: "somewhere" })).toThrow();
  });

  it("words trash events and names who did them", () => {
    expect(activityHeadline(parseActivityItem(trashed), "en")).toBe(
      "Moved to trash",
    );
    const system = parseActivityItem({
      ...trashed,
      action: "purged",
      system: true,
      actor: { kind: "system", name: null, is_self: false, avatar_url: null },
    });
    expect(activityHeadline(system, "en")).toBe(
      "Purged automatically after 90 days",
    );
    expect(activityItemActor(system).kind).toBe("system");
  });

  it("uses the trash and restore icons", () => {
    expect(activityIcon("trashed")).toBe("trash");
    expect(activityIcon("restored")).toBe("reset");
    expect(activityIcon("purged")).toBe("action-delete");
    expect(activityIcon("step_trashed")).toBe("trash");
    expect(activityIcon("step_restored")).toBe("reset");
  });

  it("never folds lifecycle events into a run", () => {
    const a = parseActivityItem({ ...trashed, id: 2, first_event_id: 2 });
    const b = parseActivityItem({ ...trashed, id: 1, first_event_id: 1 });
    expect(mergeActivityRuns([a, b])).toHaveLength(2);
  });

  it("names the owner and the state apart", () => {
    const item = parseActivityItem({
      ...trashed,
      subject: { id: 2, name: "Anna", role: null, avatar_url: "/a.png" },
    });
    expect(activityStateNote(item, "en")).toBe("In the trash · 89 days left");
    expect(activityOwner(item, "en")).toEqual({
      kind: "user",
      name: "Anna",
      avatarUrl: "/a.png",
    });
    expect(activityStateNote(parseActivityItem(raw), "en")).toBe("");
    expect(activityOwner(parseActivityItem(raw), "en")).toBeNull();
    const restored = parseActivityItem({
      ...trashed,
      action: "restored",
      subject: { id: 2, name: "Anna", role: null },
    });
    expect(activityOwner(restored, "en")?.name).toBe("Anna");
  });
});
