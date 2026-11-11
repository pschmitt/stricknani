import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import { parseHistoryEvent } from "#lib/features/history/history-api.ts";
import { parseAuditMedia, parseChangeSet, type AuditMedia } from "./admin-api";
import AuditMediaPreview from "./AuditMediaPreview.svelte";

const picture: AuditMedia = {
  kind: "image",
  id: 12,
  name: "front.png",
  exists: true,
  is_image: true,
  content_type: "image/png",
  size_bytes: 120,
  url: "/api/v2/admin/resources/project/7/media/image/12",
  thumbnail_url: "/api/v2/admin/resources/project/7/media/image/12/thumbnail",
};
const file: AuditMedia = {
  ...picture,
  kind: "attachment",
  name: "chart.pdf",
  is_image: false,
  content_type: "application/pdf",
  url: "/media/projects/7/opaque.pdf",
  thumbnail_url: null,
};
const gone = (media: AuditMedia): AuditMedia => ({
  ...media,
  exists: false,
  url: null,
  thumbnail_url: null,
});

describe("AuditMediaPreview", () => {
  it("shows a thumbnail in the note for an existing picture only", () => {
    const shown = render(AuditMediaPreview, {
      props: {
        media: picture,
        locale: "en" as const,
        variant: "note" as const,
      },
    });
    expect(shown.body).toContain("media-note__thumb");
    expect(shown.body).toContain(
      "/api/v2/admin/resources/project/7/media/image/12/thumbnail",
    );
    for (const media of [gone(picture), file]) {
      const { body } = render(AuditMediaPreview, {
        props: { media, locale: "en" as const, variant: "note" as const },
      });
      expect(body).not.toContain("media-note__thumb");
    }
  });

  it("offers a viewer for an existing picture", () => {
    const { body } = render(AuditMediaPreview, {
      props: { media: picture, locale: "en" as const },
    });
    expect(body).toContain("data-audit-media-show");
    expect(body).toContain("Show picture");
    expect(body).toContain(
      'src="/api/v2/admin/resources/project/7/media/image/12"',
    );
    expect(body).not.toContain("data-audit-media-deleted");
  });

  it("offers open/download for a file", () => {
    const { body } = render(AuditMediaPreview, {
      props: { media: file, locale: "en" as const },
    });
    expect(body).toContain("data-audit-media-open");
    expect(body).toContain('href="/media/projects/7/opaque.pdf"');
    expect(body).toContain('rel="noopener noreferrer"');
    expect(body).toContain("Open file");
  });

  it("omits the action control for deleted media", () => {
    for (const [media, text] of [
      [gone(picture), "Picture deleted"],
      [gone(file), "File deleted"],
    ] as const) {
      const { body } = render(AuditMediaPreview, {
        props: { media, locale: "en" as const },
      });
      expect(body).not.toContain("data-audit-media-deleted");
      expect(body).not.toContain(text);
      expect(body).not.toContain("href=");
      expect(body).not.toContain("<img");
    }
  });

  it("shows name, kind and deleted state in the note", () => {
    const note = render(AuditMediaPreview, {
      props: { media: gone(file), locale: "en" as const, variant: "note" },
    }).body;
    expect(note).toContain("chart.pdf");
    expect(note).toContain("File");
    expect(note).toContain("deleted");
  });

  it("keeps deleted media status in the note variant", () => {
    const { body } = render(AuditMediaPreview, {
      props: {
        media: gone(picture),
        locale: "en" as const,
        variant: "note" as const,
      },
    });
    expect(body).toContain("front.png");
    expect(body).toContain("Picture");
    expect(body).toContain("deleted");
  });
});

describe("audit media parsing", () => {
  it("accepts same-origin media links only", () => {
    expect(parseAuditMedia(picture)).toMatchObject({ id: 12, exists: true });
    expect(parseAuditMedia({ ...picture, url: "https://evil.example/x" })).toBe(
      null,
    );
    expect(parseAuditMedia({ ...picture, url: "//evil.example/x" })).toBe(null);
    expect(parseAuditMedia({ ...picture, kind: "video" })).toBe(null);
    expect(parseAuditMedia(null)).toBe(null);
  });

  it("treats media without a link as deleted", () => {
    expect(parseAuditMedia({ ...picture, url: null })).toMatchObject({
      exists: false,
    });
  });

  it("carries media on history events", () => {
    const event = parseHistoryEvent({
      id: 4,
      actor_email: null,
      actor_avatar_url: null,
      entity_type: "project",
      entity_id: 7,
      action: "uploaded",
      created_at: "2026-11-01T12:00:00Z",
      has_changes: false,
      media: file,
    });
    expect(event.media?.name).toBe("chart.pdf");
  });

  it("parses the order and cover diffs of media changes", () => {
    const changes = parseChangeSet({
      version: 1,
      items: [
        {
          field: "project.image_order",
          before: { type: "text_list", value: ["a.png", "b.png"] },
          after: { type: "text_list", value: ["b.png", "a.png"] },
        },
        {
          field: "project.cover_image",
          before: { type: "text", value: "a.png" },
          after: { type: "text", value: "b.png" },
        },
        {
          field: "yarn.photo_order",
          before: { type: "text_list", value: [] },
          after: { type: "text_list", value: ["x.png"] },
        },
      ],
    });
    expect(changes.items).toHaveLength(3);
  });
});
