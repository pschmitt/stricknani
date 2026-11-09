import type { ComponentProps } from "svelte";
import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import type { AuditMedia } from "#lib/features/admin/admin-api.ts";
import HistoryEventDetail from "./HistoryEventDetail.svelte";
import type { HistoryEvent } from "./history-api";

type DetailProp = ComponentProps<typeof HistoryEventDetail>["detail"];
import { historyDetailKind } from "./history-summary";

const event = (patch: Partial<HistoryEvent> = {}): HistoryEvent => ({
  id: 5,
  actor_email: "a@example.org",
  actor_avatar_url: null,
  entity_type: "project",
  entity_id: 7,
  action: "updated",
  created_at: "2026-05-01T10:00:00Z",
  has_changes: true,
  media: null,
  media_thumbnails: null,
  impersonated_by: null,
  ...patch,
});

const picture: AuditMedia = {
  kind: "image",
  id: 12,
  name: "front.png",
  exists: true,
  is_image: true,
  content_type: "image/png",
  size_bytes: 120,
  url: "/media/projects/7/front.png",
  thumbnail_url: "/media/thumbnails/projects/7/front.png",
};

const props = (e: HistoryEvent, detail?: DetailProp) => ({
  event: e,
  locale: "en" as const,
  id: "history-projects-7-event-5",
  label: "Updated",
  detail,
  onretry: () => {},
});

describe("historyDetailKind", () => {
  it("prefers the file, then the recorded changes, else nothing", () => {
    expect(historyDetailKind(event({ media: picture }))).toBe("media");
    expect(historyDetailKind(event())).toBe("changes");
    expect(historyDetailKind(event({ has_changes: false }))).toBe("none");
  });
});

describe("HistoryEventDetail", () => {
  it("says so when nothing was recorded", () => {
    const { body } = render(HistoryEventDetail, {
      props: props(event({ has_changes: false })),
    });
    expect(body).toContain("data-history-no-details");
    expect(body).toContain("No details recorded");
  });

  it("shows the owner-scoped picture of an upload", () => {
    const { body } = render(HistoryEventDetail, {
      props: props(event({ action: "image_uploaded", media: picture })),
    });
    expect(body).toContain("Show picture");
    expect(body).toContain('src="/media/projects/7/front.png"');
    expect(body).not.toContain("/api/v2/admin/");
  });

  it("shows the before and after of recorded changes", () => {
    const { body } = render(HistoryEventDetail, {
      props: props(event(), {
        state: "ready",
        detail: {
          event: event(),
          unavailable_reason: null,
          changes: {
            version: 1,
            items: [
              {
                field: "project.name",
                before: { type: "text" as const, value: "Old" },
                after: { type: "text" as const, value: "New" },
              },
            ],
          },
        },
      }),
    });
    expect(body).toContain("Before");
    expect(body).toContain("After");
    expect(body).toContain("Old");
    expect(body).toContain("New");
  });

  it("reads a never-recorded snapshot as no details", () => {
    const e = event();
    const { body } = render(HistoryEventDetail, {
      props: props(e, {
        state: "ready",
        detail: { event: e, changes: null, unavailable_reason: "not_recorded" },
      }),
    });
    expect(body).toContain("No details recorded");
  });

  it("shows a loading note while the changes load", () => {
    const { body } = render(HistoryEventDetail, {
      props: props(event(), { state: "loading" }),
    });
    expect(body).toContain("Loading recorded changes");
  });
});
