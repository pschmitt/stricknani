import {
  advanceAuthEpoch,
  ApiError,
  getSession,
  login as postLogin,
  logout as postLogout,
  setUnauthorizedHandler,
  type AvatarSource,
  type SessionData,
} from "./api";
import { t } from "./i18n.svelte";

export type SessionStatus = "loading" | "ready" | "error";

interface SessionState {
  status: SessionStatus;
  data: SessionData | null;
  error: Error | null;
  expired: boolean;
}

const anonymousSession: SessionData = {
  user: null,
  csrf_token: "",
  capabilities: {
    signup: false,
    external_imports: false,
    ai: false,
    ocr: false,
    archive: false,
    media_upload: false,
    media_delete: false,
    import_preview: false,
    archive_state: false,
    account_actions: false,
    admin_actions: false,
  },
  locale: "en",
};

export const session = $state<SessionState>({
  status: "loading",
  data: null,
  error: null,
  expired: false,
});

let sessionGeneration = 0;

setUnauthorizedHandler(() => {
  if (!session.data?.user) return;
  advanceAuthEpoch();
  sessionGeneration += 1;
  session.data = anonymousSession;
  session.expired = true;
  void refreshSession(undefined, true);
});

export async function refreshSession(
  signal?: AbortSignal,
  preserveExpiration = false,
): Promise<void> {
  const generation = ++sessionGeneration;
  advanceAuthEpoch();
  const wasExpired = preserveExpiration || session.expired;
  session.status = "loading";
  session.error = null;
  try {
    const data = await getSession(signal);
    if (signal?.aborted || generation !== sessionGeneration) return;
    session.data = data;
    session.status = "ready";
    session.expired = wasExpired;
  } catch (error) {
    if (signal?.aborted || generation !== sessionGeneration) return;
    session.status = "error";
    session.error = asError(error);
    session.expired = wasExpired;
  }
}

export async function signIn(email: string, password: string): Promise<void> {
  const csrfToken = session.data?.csrf_token;
  if (!csrfToken)
    throw new ApiError(
      t("The session token is missing. Refresh and try again."),
      400,
      "csrf_missing",
    );
  const generation = ++sessionGeneration;
  advanceAuthEpoch();
  session.error = null;
  const data = await postLogin(email, password, csrfToken);
  if (generation !== sessionGeneration) return;
  advanceAuthEpoch();
  session.data = data;
  session.status = "ready";
  session.expired = false;
}

export async function signOut(): Promise<void> {
  const csrfToken = session.data?.csrf_token;
  if (!csrfToken)
    throw new ApiError(
      t("The session token is missing. Refresh and try again."),
      400,
      "csrf_missing",
    );
  const generation = ++sessionGeneration;
  advanceAuthEpoch();
  await postLogout(csrfToken);
  if (generation !== sessionGeneration) return;
  advanceAuthEpoch();
  session.data = anonymousSession;
  session.status = "ready";
  await refreshSession();
}

/**
 * The account was deleted on the server, which also cleared the session
 * cookie. Drop the local session and fetch a fresh signed-out one.
 */
export async function endSessionAfterDeletion(): Promise<void> {
  ++sessionGeneration;
  advanceAuthEpoch();
  session.data = anonymousSession;
  session.status = "ready";
  await refreshSession();
}

/** Update the current user's avatar without resetting the authenticated app tree. */
export function patchSessionAvatar(
  expectedSession: SessionData,
  avatarUrl: string | null,
  avatarSource: AvatarSource | null = avatarUrl === null ? null : "upload",
): boolean {
  if (
    session.status !== "ready" ||
    session.data !== expectedSession ||
    expectedSession.user === null
  )
    return false;
  session.data = {
    ...expectedSession,
    user: {
      ...expectedSession.user,
      avatar_url: avatarUrl,
      avatar_source: avatarSource,
    },
  };
  return true;
}

/** Mirror the person's error-reporting choice into the session after saving it. */
export function patchSessionErrorReporting(
  expectedSession: SessionData,
  enabled: boolean,
): boolean {
  if (
    session.status !== "ready" ||
    session.data !== expectedSession ||
    expectedSession.user === null ||
    !expectedSession.error_reporting
  )
    return false;
  session.data = {
    ...expectedSession,
    error_reporting: { ...expectedSession.error_reporting, enabled },
  };
  return true;
}

function asError(error: unknown): Error {
  return error instanceof Error
    ? error
    : new Error(t("The session could not be loaded."));
}
