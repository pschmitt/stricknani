import { afterEach, describe, expect, it, vi } from "vitest";
import {
  activityAgo,
  activityDetail,
  activitySentence,
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
  actor: { name: "philipp", is_self: true, avatar_url: null },
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
  it("builds the sentence around a bold name", () => {
    expect(activitySentence(parseActivityItem(raw), "en")).toEqual({
      before: "You updated ",
      name: "Winter socks",
      after: "",
    });
  });

  it("names an administrator and falls back for unknown actions", () => {
    const item = parseActivityItem({
      ...raw,
      action: "step_updated",
      actor: { name: null, is_self: false, avatar_url: null },
    });
    expect(activitySentence(item, "en").before).toBe("An admin changed ");
  });

  it("uses a generic noun when the name is unknown", () => {
    const item = parseActivityItem({
      ...raw,
      entity_name: null,
      entity_type: "yarn",
      action: "deleted",
    });
    expect(activitySentence(item, "en")).toEqual({
      before: "You deleted ",
      name: null,
      after: "a yarn",
    });
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
