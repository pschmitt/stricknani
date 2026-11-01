import {
  ApiError,
  getSession,
  login as postLogin,
  logout as postLogout,
  setUnauthorizedHandler,
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
  },
  locale: "en",
};

export const session = $state<SessionState>({
  status: "loading",
  data: null,
  error: null,
  expired: false,
});

setUnauthorizedHandler(() => {
  if (!session.data?.user) return;
  session.data = anonymousSession;
  session.expired = true;
  void refreshSession(undefined, true);
});

export async function refreshSession(
  signal?: AbortSignal,
  preserveExpiration = false,
): Promise<void> {
  const wasExpired = preserveExpiration || session.expired;
  session.status = "loading";
  session.error = null;
  try {
    session.data = await getSession(signal);
    session.status = "ready";
    session.expired = wasExpired;
  } catch (error) {
    if (signal?.aborted) return;
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
  session.error = null;
  session.data = await postLogin(email, password, csrfToken);
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
  await postLogout(csrfToken);
  session.data = anonymousSession;
  session.status = "ready";
  await refreshSession();
}

function asError(error: unknown): Error {
  return error instanceof Error
    ? error
    : new Error(t("The session could not be loaded."));
}
