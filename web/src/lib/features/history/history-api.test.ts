import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import {
  getHistoryDetail,
  historyUnsupported,
  listHistory,
  parseHistoryPage,
} from "./history-api";

const event = (id: number, extra: Record<string, unknown> = {}) => ({
  id,
  actor_email: "a@example.invalid",
  actor_avatar_url: null,
  entity_type: "project",
  entity_id: 5,
  action: "updated",
  created_at: "2026-05-01T10:00:00Z",
  resource: { kind: "project", id: 5 },
  has_changes: true,
  ...extra,
});

afterEach(() => vi.unstubAllGlobals());

function stubFetch(status: number, body: unknown) {
  const fetchMock = vi.fn(
    async () =>
      new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json" },
      }),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("history API", () => {
  it("parses a page of events", () => {
    const page = parseHistoryPage({
      data: [event(2), event(1, { has_changes: false })],
      meta: { page: 1, per_page: 20, has_more: true },
    });
    expect(page.data.map((e) => e.id)).toEqual([2, 1]);
    expect(page.data[1].has_changes).toBe(false);
    expect(page.meta.has_more).toBe(true);
  });

  it("keeps the person of a sharing event", () => {
    const page = parseHistoryPage({
      data: [
        event(3, {
          action: "ownership_transferred",
          subject: {
            id: 2,
            name: "Anika",
            role: null,
            avatar_url: "/api/v2/people/2/avatar/a.png",
          },
          actor_name: "Anika Bergmann",
          actor_is_self: true,
        }),
        event(2, { action: "shared", subject: "bogus" }),
      ],
      meta: { page: 1, per_page: 20, has_more: false },
    });
    expect(page.data[0].subject).toEqual({
      id: 2,
      name: "Anika",
      role: null,
      avatar_url: "/api/v2/people/2/avatar/a.png",
    });
    expect(page.data[0].actor_name).toBe("Anika Bergmann");
    expect(page.data[0].actor_is_self).toBe(true);
    expect(page.data[1].actor_is_self).toBe(false);
    expect(page.data[1].actor_name).toBeNull();
    expect(page.data[1].subject).toBeNull();
  });

  it("rejects malformed events and drops non-local avatars", () => {
    expect(() =>
      parseHistoryPage({ data: [{ id: 1 }], meta: { page: 1 } }),
    ).toThrow();
    const page = parseHistoryPage({
      data: [event(1, { actor_avatar_url: "https://evil.invalid/x.png" })],
      meta: { page: 1, per_page: 20, has_more: false },
    });
    expect(page.data[0].actor_avatar_url).toBeNull();
  });

  it("requests the per-resource endpoints", async () => {
    const fetchMock = stubFetch(200, {
      data: [event(1)],
      meta: { page: 2, per_page: 20, has_more: false },
    });
    const page = await listHistory("yarns", 9, 2);
    expect(page.meta.page).toBe(2);
    expect(String((fetchMock.mock.calls[0] as unknown[])[0])).toContain(
      "/api/v2/yarns/9/history?page=2",
    );
  });

  it("parses event detail with a change set", async () => {
    stubFetch(200, {
      data: {
        event: event(3),
        changes: {
          version: 1,
          items: [
            {
              field: "project.name",
              before: { type: "text", value: "a" },
              after: { type: "text", value: "b" },
            },
          ],
        },
        unavailable_reason: null,
      },
    });
    const detail = await getHistoryDetail("projects", 5, 3);
    expect(detail.changes?.items).toHaveLength(1);
  });

  it("treats 404 and 405 as an unsupported server", async () => {
    stubFetch(404, { code: "not_found", message: "nope" });
    const error = await listHistory("projects", 1, 1).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(historyUnsupported(error)).toBe(true);
    expect(historyUnsupported(new ApiError("x", 405))).toBe(true);
    expect(historyUnsupported(new ApiError("x", 500))).toBe(false);
  });
});
