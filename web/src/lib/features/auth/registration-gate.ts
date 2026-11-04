import type { SessionData } from "#lib/api.ts";

interface SessionSlice {
  status: "loading" | "ready" | "error";
  data: Pick<SessionData, "capabilities" | "user"> | null;
}

/** Sign-up form and the sign-in page's "Create an account" link are shown. */
export function registrationOpen(session: SessionSlice): boolean {
  return (
    session.status === "ready" &&
    session.data !== null &&
    session.data.user === null &&
    session.data.capabilities.signup
  );
}

/**
 * The "set up your own instance" card replaces the link while registration is
 * closed. It waits for the session so the page does not flash the card before
 * the server has said whether registration is open.
 */
export function selfHostCardVisible(session: SessionSlice): boolean {
  return (
    session.status === "ready" &&
    session.data !== null &&
    session.data.user === null &&
    !session.data.capabilities.signup
  );
}
