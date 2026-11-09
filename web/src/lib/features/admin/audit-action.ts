import { msg } from "#lib/i18n.svelte.ts";

export type AuditActionIcon =
  | "action-create"
  | "action-update"
  | "action-delete"
  | "action-upload"
  | "action-remove"
  | "action-default";

export type AuditActionPresentation =
  | {
      known: true;
      icon: AuditActionIcon;
      messageKey: string;
    }
  | {
      known: false;
      icon: "action-default";
      label: string;
    };

const knownActions: Record<
  string,
  { icon: AuditActionIcon; messageKey: string }
> = {
  created: { icon: "action-create", messageKey: msg("Created") },
  updated: { icon: "action-update", messageKey: msg("Updated") },
  deleted: { icon: "action-delete", messageKey: msg("Deleted") },
  uploaded: { icon: "action-upload", messageKey: msg("Uploaded") },
  removed: { icon: "action-remove", messageKey: msg("Removed") },
  step_created: { icon: "action-create", messageKey: msg("Step created") },
  step_updated: { icon: "action-update", messageKey: msg("Step updated") },
  step_deleted: { icon: "action-delete", messageKey: msg("Step deleted") },
  title_image_uploaded: {
    icon: "action-upload",
    messageKey: msg("Title image uploaded"),
  },
  stitch_sample_image_uploaded: {
    icon: "action-upload",
    messageKey: msg("Stitch sample image uploaded"),
  },
  step_image_uploaded: {
    icon: "action-upload",
    messageKey: msg("Step image uploaded"),
  },
  attachment_uploaded: {
    icon: "action-upload",
    messageKey: msg("Attachment uploaded"),
  },
  attachment_deleted: {
    icon: "action-delete",
    messageKey: msg("Attachment deleted"),
  },
  image_deleted: { icon: "action-delete", messageKey: msg("Image deleted") },
  photo_uploaded: { icon: "action-upload", messageKey: msg("Photo uploaded") },
  photo_deleted: { icon: "action-delete", messageKey: msg("Photo deleted") },
  shared: { icon: "action-create", messageKey: msg("Shared") },
  share_role_changed: {
    icon: "action-update",
    messageKey: msg("Sharing role changed"),
  },
  share_revoked: { icon: "action-remove", messageKey: msg("Sharing stopped") },
  share_left: {
    icon: "action-remove",
    messageKey: msg("Left the shared item"),
  },
  ownership_transferred: {
    icon: "action-update",
    messageKey: msg("Ownership transferred"),
  },
  photo_promoted: {
    icon: "action-update",
    messageKey: msg("Cover photo changed"),
  },
  title_image_promoted: {
    icon: "action-update",
    messageKey: msg("Title image changed"),
  },
  impersonation_started: {
    icon: "action-default",
    messageKey: msg("Impersonation started"),
  },
  impersonation_stopped: {
    icon: "action-default",
    messageKey: msg("Impersonation stopped"),
  },
};

/** Every action the audit log knows by name (for filter suggestions). */
export const knownAuditActions: Readonly<typeof knownActions> = knownActions;

export function auditActionPresentation(
  action: string,
): AuditActionPresentation {
  const known = Object.hasOwn(knownActions, action)
    ? knownActions[action]
    : undefined;
  return known
    ? { known: true, ...known }
    : { known: false, icon: "action-default", label: action };
}
