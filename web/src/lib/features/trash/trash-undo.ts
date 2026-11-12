import { ApiError, type SessionData } from "#lib/api.ts";
import { publishFeedback } from "#lib/feedback.ts";
import { t, type Locale } from "#lib/i18n.svelte.ts";
import { restoreTrashItem } from "./trash-api";
import type { TrashKind } from "./trash-kinds";

export interface TrashedFeedback {
  session: SessionData;
  ownerId: number;
  locale?: Locale;
  kind: TrashKind;
  id: number;
  /** Runs after a successful undo, for example to open or reload the item. */
  onRestored?: () => void | Promise<void>;
}

/** Message of a restore failure; a parent that is still in the trash gets its own hint. */
export function restoreErrorMessage(error: unknown, locale?: Locale): string {
  if (error instanceof ApiError) {
    if (error.status === 404)
      return t("This item is no longer in the trash.", locale);
    return error.message;
  }
  return t("The item could not be restored.", locale);
}

/**
 * Tells the user that an item went to the trash and offers "Undo", which restores it. The
 * toast stays longer than a plain one so the button can be reached.
 */
export function publishMovedToTrash(options: TrashedFeedback): number {
  const { session, ownerId, locale, kind, id, onRestored } = options;
  return publishFeedback({
    ownerId,
    kind: "deleted",
    message: t("Moved to the trash.", locale),
    action: {
      label: t("Undo", locale),
      onAction: async () => {
        try {
          await restoreTrashItem(kind, id, session);
        } catch (error) {
          publishFeedback({
            ownerId,
            kind: "error",
            message: restoreErrorMessage(error, locale),
          });
          return;
        }
        publishFeedback({
          ownerId,
          kind: "restored",
          message: t("Restored from the trash.", locale),
        });
        try {
          await onRestored?.();
        } catch {
          // The item is back; a failed refresh is not worth a second error.
        }
      },
    },
  });
}

export interface SavedRemovalFeedback {
  session: SessionData;
  ownerId: number;
  locale?: Locale;
  /** What the save moved to the trash, in the order it is restored (steps before their images). */
  items: { kind: TrashKind; id: number }[];
  /** Runs after a successful undo, for example to reload the saved item. */
  onRestored?: () => void | Promise<void>;
}

/**
 * Confirms a save that left steps or photos out (the server trashes them) and offers "Undo",
 * which restores exactly those items.
 */
export function publishSavedWithRemovals(
  options: SavedRemovalFeedback,
): number {
  const { session, ownerId, locale, items, onRestored } = options;
  return publishFeedback({
    ownerId,
    kind: "deleted",
    message: t("Saved. What you removed is in the trash for 90 days.", locale),
    action: {
      label: t("Undo", locale),
      onAction: async () => {
        try {
          for (const item of items) {
            await restoreTrashItem(item.kind, item.id, session);
          }
        } catch (error) {
          publishFeedback({
            ownerId,
            kind: "error",
            message: restoreErrorMessage(error, locale),
          });
          return;
        }
        publishFeedback({
          ownerId,
          kind: "restored",
          message: t("Restored from the trash.", locale),
        });
        try {
          await onRestored?.();
        } catch {
          // The items are back; a failed refresh is not worth a second error.
        }
      },
    },
  });
}
