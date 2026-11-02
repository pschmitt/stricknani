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
});
