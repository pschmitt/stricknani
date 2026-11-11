import { describe, expect, it } from "vitest";
import { auditActionPresentation } from "./audit-action";

describe("auditActionPresentation", () => {
  it.each([
    ["step_created", "action-create", "Step created"],
    ["step_updated", "action-update", "Step updated"],
    ["step_deleted", "action-delete", "Step deleted"],
    ["title_image_uploaded", "action-upload", "Title image uploaded"],
    [
      "stitch_sample_image_uploaded",
      "action-upload",
      "Stitch sample image uploaded",
    ],
    ["step_image_uploaded", "action-upload", "Step image uploaded"],
    ["attachment_uploaded", "action-upload", "Attachment uploaded"],
    ["attachment_deleted", "action-delete", "Attachment deleted"],
    ["image_deleted", "action-delete", "Image deleted"],
    ["photo_uploaded", "action-upload", "Photo uploaded"],
    ["photo_deleted", "action-delete", "Photo deleted"],
    ["photo_promoted", "action-update", "Cover photo changed"],
    ["title_image_promoted", "action-update", "Title image changed"],
    ["impersonation_started", "action-default", "Impersonation started"],
    ["impersonation_stopped", "action-default", "Impersonation stopped"],
    ["trashed", "trash", "Moved to trash"],
    ["restored", "reset", "Restored from trash"],
    ["purged", "action-delete", "Deleted forever"],
    ["deleted", "action-delete", "Deleted permanently"],
  ] as const)(
    "maps %s to its action family and specific label",
    (action, icon, label) => {
      expect(auditActionPresentation(action)).toEqual({
        known: true,
        icon,
        messageKey: label,
      });
    },
  );

  it("keeps unknown historical action text with the generic icon", () => {
    for (const action of [
      "legacy_custom_change",
      "constructor",
      "toString",
      "__proto__",
    ]) {
      expect(auditActionPresentation(action)).toEqual({
        known: false,
        icon: "action-default",
        label: action,
      });
    }
  });

  it("words the automatic purge as the system's, not a person's", () => {
    expect(auditActionPresentation("purged", { system: true })).toEqual({
      known: true,
      icon: "action-delete",
      messageKey: "Purged automatically after 90 days",
    });
    expect(auditActionPresentation("purged", { system: false })).toMatchObject({
      messageKey: "Deleted forever",
    });
    expect(auditActionPresentation("trashed", { system: true })).toMatchObject({
      messageKey: "Moved to trash",
    });
  });

  it("words step and file trash events by what they are about", () => {
    expect(auditActionPresentation("step_trashed")).toMatchObject({
      icon: "trash",
      messageKey: "Step moved to trash",
    });
    expect(auditActionPresentation("step_purged")).toMatchObject({
      messageKey: "Step deleted forever",
    });
    expect(
      auditActionPresentation("trashed", { mediaKind: "image" }),
    ).toMatchObject({ messageKey: "Image moved to trash" });
    expect(
      auditActionPresentation("restored", { mediaKind: "attachment" }),
    ).toMatchObject({ messageKey: "Attachment restored from trash" });
    expect(
      auditActionPresentation("purged", { mediaKind: "photo" }),
    ).toMatchObject({ messageKey: "Photo deleted forever" });
    expect(auditActionPresentation("removed")).toMatchObject({
      messageKey: "Removed",
    });
  });
});
