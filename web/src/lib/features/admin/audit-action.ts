import { msg } from "#lib/i18n.svelte.ts";

export type AuditActionIcon =
  | "action-create"
  | "action-update"
  | "action-delete"
  | "action-upload"
  | "action-remove"
  | "action-default"
  | "trash"
  | "reset";

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
  // Before the trash existed a deletion destroyed the item; those rows keep this action.
  deleted: { icon: "action-delete", messageKey: msg("Deleted permanently") },
  trashed: { icon: "trash", messageKey: msg("Moved to trash") },
  restored: { icon: "reset", messageKey: msg("Restored from trash") },
  purged: { icon: "action-delete", messageKey: msg("Deleted forever") },
  step_trashed: { icon: "trash", messageKey: msg("Step moved to trash") },
  step_restored: { icon: "reset", messageKey: msg("Step restored from trash") },
  step_purged: {
    icon: "action-delete",
    messageKey: msg("Step deleted forever"),
  },
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
  timer_started: { icon: "action-create", messageKey: msg("Timer started") },
  timer_stopped: { icon: "action-update", messageKey: msg("Timer stopped") },
  timer_total_changed: {
    icon: "action-update",
    messageKey: msg("Knitting time changed"),
  },
  photo_promoted: {
    icon: "action-update",
    messageKey: msg("Cover photo changed"),
  },
  title_image_promoted: {
    icon: "action-update",
    messageKey: msg("Title image changed"),
  },
  backup_restored: { icon: "reset", messageKey: msg("Backup restored") },
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

function mediaPresentation(
  action: string,
  kind: "image" | "attachment" | "photo" | null,
): AuditActionPresentation | null {
  if (kind === null) return null;
  const table: Record<string, Record<string, string>> = {
    trashed: {
      image: msg("Image moved to trash"),
      attachment: msg("Attachment moved to trash"),
      photo: msg("Photo moved to trash"),
    },
    restored: {
      image: msg("Image restored from trash"),
      attachment: msg("Attachment restored from trash"),
      photo: msg("Photo restored from trash"),
    },
    purged: {
      image: msg("Image deleted forever"),
      attachment: msg("Attachment deleted forever"),
      photo: msg("Photo deleted forever"),
    },
  };
  const messageKey = table[action]?.[kind];
  if (!messageKey) return null;
  return {
    known: true,
    icon:
      action === "trashed"
        ? "trash"
        : action === "restored"
          ? "reset"
          : "action-delete",
    messageKey,
  };
}

/**
 * How an action reads. `system` marks the automatic purge of the trash, which has no actor and
 * reads "Purged automatically after 90 days" instead of "Deleted forever".
 */
export function auditActionPresentation(
  action: string,
  options: {
    system?: boolean;
    /** The kind of file a trash event is about; words it "Image moved to trash" etc. */
    mediaKind?: "image" | "attachment" | "photo" | null;
  } = {},
): AuditActionPresentation {
  const media = mediaPresentation(action, options.mediaKind ?? null);
  if (media) return media;
  if (action === "purged" && options.system === true)
    return {
      known: true,
      icon: "action-delete",
      messageKey: msg("Purged automatically after 90 days"),
    };
  const known = Object.hasOwn(knownActions, action)
    ? knownActions[action]
    : undefined;
  return known
    ? { known: true, ...known }
    : { known: false, icon: "action-default", label: action };
}
