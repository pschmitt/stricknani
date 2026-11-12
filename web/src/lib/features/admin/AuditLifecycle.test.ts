import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import type { AuditEvent } from "./admin-api";
import AuditLifecycle from "./AuditLifecycle.svelte";

const trash = {
  deleted_at: "2026-11-01T12:00:00Z",
  purge_at: "2027-01-30T12:00:00Z",
  days_remaining: 12,
  can_restore: true,
};

const event = (patch: Partial<AuditEvent> = {}): AuditEvent => ({
  id: 21,
  actor_email: "admin@example.invalid",
  actor_avatar_url: null,
  legacy_actor_user_id: null,
  entity_type: "project",
  entity_id: 3,
  action: "trashed",
  created_at: "2026-11-01T12:00:00Z",
  has_changes: false,
  media: null,
  media_thumbnails: null,
  impersonated_by: null,
  system: false,
  subject: null,
  client: { kind: "unknown", version: null, label: null, user_agent: null },
  resource: {
    kind: "project",
    id: 3,
    name: "Socks",
    name_source: "event",
    href: null,
    preview_url: null,
    exists: false,
    state: "in_trash",
    trash,
  },
  ...patch,
});

const html = (e: AuditEvent) =>
  render(AuditLifecycle, {
    props: { event: e, locale: "en" as const, onChanged: () => {} },
  }).body;

describe("AuditLifecycle", () => {
  it("shows the days left, the Trash link and Restore while it can be restored", () => {
    const body = html(event());
    expect(body).toContain("In the trash · 12 days left");
    expect(body).toContain('data-testid="audit-open-trash"');
    expect(body).toContain("scope=all");
    expect(body).toContain('data-testid="audit-restore"');
    expect(body).not.toContain("/projects/3");
  });

  it("offers no Restore when the server says it cannot be restored", () => {
    const e = event();
    e.resource.trash = { ...trash, can_restore: false };
    const body = html(e);
    expect(body).toContain("In the trash");
    expect(body).not.toContain("audit-restore");
  });

  it("reads restored and permanently deleted entries without actions", () => {
    const restored = event();
    restored.resource = { ...restored.resource, state: "active", trash: null };
    expect(html(restored)).toContain("Restored");
    expect(html(restored)).not.toContain("audit-restore");
    const gone = event();
    gone.resource = { ...gone.resource, state: "gone", trash: null };
    expect(html(gone)).toContain("Deleted permanently");
    expect(html(gone)).not.toContain("audit-open-trash");
  });

  it("names the owner of an administrator's or the system's action", () => {
    const body = html(
      event({ subject: { id: 2, name: "Anna" }, system: false }),
    );
    // The owner is a UserLabel (avatar + name), not plain text.
    expect(body).toContain("user-label");
    expect(body.replace(/<[^>]*>/g, "")).toMatch(/Owner:\s*A\s*Anna/);
  });

  it("renders nothing for an ordinary event", () => {
    const e = event({ action: "updated" });
    e.resource = { ...e.resource, state: null, trash: null, exists: true };
    expect(html(e)).not.toContain("audit-lifecycle");
  });

  it("links a file or step event to the Trash only, never Restore", () => {
    const file = event({
      media: {
        kind: "image",
        id: 4,
        name: "a.png",
        exists: false,
        is_image: true,
        content_type: null,
        size_bytes: null,
        url: null,
        thumbnail_url: null,
      },
    });
    file.resource = { ...file.resource, trash: null };
    const body = html(file);
    expect(body).toContain("audit-open-trash");
    expect(body).not.toContain("audit-restore");
    expect(body).not.toContain("In the trash");
    const stepEvent = event({ action: "step_trashed" });
    stepEvent.resource = { ...stepEvent.resource, trash: null };
    const step = html(stepEvent);
    expect(step).toContain("audit-open-trash");
    expect(step).not.toContain("audit-restore");
  });
});
