import type {
  BackupEntry,
  MediaOption,
  RestoreAccepted,
  RestorePreview,
} from "./backups-api";

/**
 * State machine of the multi-step restore dialog:
 * preview -> (re-authentication) -> type the backup name -> restore now ->
 * "the server is restarting" -> success or failure. The reducer is pure; the
 * dialog performs the requests and feeds their results back as events.
 */

/** How long a confirmed re-authentication stays valid on the server (10 minutes). */
export const REAUTH_WINDOW_MS = 10 * 60 * 1000;

export type RestoreErrorKind =
  | "newer_schema"
  | "unreadable"
  | "busy"
  | "pre_restore_failed"
  | "stage_failed"
  | "confirmation"
  | "media"
  | "unavailable"
  | "unknown";

export type RestoreState =
  | { phase: "closed" }
  | { phase: "loading"; backup: BackupEntry; reauthedAt: number | null }
  | {
      phase: "preview";
      backup: BackupEntry;
      preview: RestorePreview;
      media: MediaOption;
      reauthedAt: number | null;
    }
  | {
      phase: "reauth";
      backup: BackupEntry;
      preview: RestorePreview;
      media: MediaOption;
      reauthedAt: number | null;
    }
  | {
      phase: "confirm";
      backup: BackupEntry;
      preview: RestorePreview;
      media: MediaOption;
      reauthedAt: number;
      typed: string;
      error: RestoreErrorKind | null;
    }
  | {
      phase: "restoring";
      backup: BackupEntry;
      preview: RestorePreview;
      media: MediaOption;
      reauthedAt: number;
      typed: string;
    }
  | {
      phase: "waiting";
      backup: BackupEntry;
      accepted: RestoreAccepted;
      requestedAt: number;
    }
  | {
      phase: "manual_restart";
      backup: BackupEntry;
      accepted: RestoreAccepted;
    }
  | { phase: "succeeded"; backup: BackupEntry; accepted: RestoreAccepted }
  | {
      phase: "failed";
      backup: BackupEntry;
      accepted: RestoreAccepted;
      error: string | null;
    }
  | { phase: "timeout"; backup: BackupEntry; accepted: RestoreAccepted }
  | { phase: "error"; backup: BackupEntry; error: RestoreErrorKind };

export type RestoreEvent =
  | { type: "open"; backup: BackupEntry; reauthedAt?: number | null }
  | { type: "preview_loaded"; preview: RestorePreview }
  | { type: "preview_failed"; error: RestoreErrorKind }
  | { type: "set_media"; media: MediaOption }
  | { type: "continue"; now: number }
  | { type: "reauth_confirmed"; now: number }
  | { type: "reauth_cancelled" }
  | { type: "typed"; value: string }
  | { type: "submit" }
  | { type: "accepted"; accepted: RestoreAccepted; now: number }
  | { type: "rejected"; error: RestoreErrorKind; reauthRequired?: boolean }
  | {
      type: "server_back";
      result: "succeeded" | "failed" | "timeout";
      error?: string | null;
    }
  | { type: "back" }
  | { type: "close" };

export const CLOSED: RestoreState = { phase: "closed" };

/** A confirmation counts when it equals the backup's exact file name. */
export function confirmationMatches(typed: string, name: string): boolean {
  return typed === name;
}

export function reauthIsFresh(
  reauthedAt: number | null,
  now: number,
): reauthedAt is number {
  return (
    reauthedAt !== null &&
    now >= reauthedAt &&
    now - reauthedAt < REAUTH_WINDOW_MS - 15_000
  );
}

/** The "Restore now" button is only enabled with the exact name and no request in flight. */
export function canRestore(state: RestoreState): boolean {
  return (
    state.phase === "confirm" &&
    confirmationMatches(state.typed, state.backup.name)
  );
}

/** The dialog cannot be dismissed while the server works on the restore. */
export function isLocked(state: RestoreState): boolean {
  return state.phase === "restoring" || state.phase === "waiting";
}

export function restoreReducer(
  state: RestoreState,
  event: RestoreEvent,
): RestoreState {
  switch (event.type) {
    case "open":
      if (state.phase !== "closed" || !event.backup.restorable) return state;
      return {
        phase: "loading",
        backup: event.backup,
        reauthedAt: event.reauthedAt ?? null,
      };
    case "preview_loaded":
      return state.phase === "loading"
        ? {
            phase: "preview",
            backup: state.backup,
            preview: event.preview,
            media: "none",
            reauthedAt: state.reauthedAt,
          }
        : state;
    case "preview_failed":
      return state.phase === "loading"
        ? { phase: "error", backup: state.backup, error: event.error }
        : state;
    case "set_media":
      if (state.phase !== "preview" && state.phase !== "confirm") return state;
      return state.preview.media_options.includes(event.media)
        ? { ...state, media: event.media }
        : state;
    case "continue":
      if (state.phase !== "preview") return state;
      return reauthIsFresh(state.reauthedAt, event.now)
        ? {
            phase: "confirm",
            backup: state.backup,
            preview: state.preview,
            media: state.media,
            reauthedAt: state.reauthedAt,
            typed: "",
            error: null,
          }
        : {
            phase: "reauth",
            backup: state.backup,
            preview: state.preview,
            media: state.media,
            reauthedAt: state.reauthedAt,
          };
    case "reauth_confirmed":
      return state.phase === "reauth"
        ? {
            phase: "confirm",
            backup: state.backup,
            preview: state.preview,
            media: state.media,
            reauthedAt: event.now,
            typed: "",
            error: null,
          }
        : state;
    case "reauth_cancelled":
      return state.phase === "reauth"
        ? {
            phase: "preview",
            backup: state.backup,
            preview: state.preview,
            media: state.media,
            reauthedAt: state.reauthedAt,
          }
        : state;
    case "typed":
      return state.phase === "confirm"
        ? { ...state, typed: event.value, error: null }
        : state;
    case "submit":
      return canRestore(state) && state.phase === "confirm"
        ? {
            phase: "restoring",
            backup: state.backup,
            preview: state.preview,
            media: state.media,
            reauthedAt: state.reauthedAt,
            typed: state.typed,
          }
        : state;
    case "accepted":
      if (state.phase !== "restoring") return state;
      return event.accepted.restarting
        ? {
            phase: "waiting",
            backup: state.backup,
            accepted: event.accepted,
            requestedAt: event.now,
          }
        : {
            phase: "manual_restart",
            backup: state.backup,
            accepted: event.accepted,
          };
    case "rejected":
      if (state.phase !== "restoring") return state;
      if (event.reauthRequired) {
        // The server's window closed: ask again, keep the choices.
        return {
          phase: "reauth",
          backup: state.backup,
          preview: state.preview,
          media: state.media,
          reauthedAt: null,
        };
      }
      return {
        phase: "confirm",
        backup: state.backup,
        preview: state.preview,
        media: state.media,
        reauthedAt: state.reauthedAt,
        typed: state.typed,
        error: event.error,
      };
    case "server_back": {
      if (state.phase !== "waiting") return state;
      if (event.result === "succeeded")
        return {
          phase: "succeeded",
          backup: state.backup,
          accepted: state.accepted,
        };
      if (event.result === "timeout")
        return {
          phase: "timeout",
          backup: state.backup,
          accepted: state.accepted,
        };
      return {
        phase: "failed",
        backup: state.backup,
        accepted: state.accepted,
        error: event.error ?? null,
      };
    }
    case "back":
      if (state.phase === "confirm")
        return {
          phase: "preview",
          backup: state.backup,
          preview: state.preview,
          media: state.media,
          reauthedAt: state.reauthedAt,
        };
      return state;
    case "close":
      return isLocked(state) ? state : CLOSED;
  }
}
