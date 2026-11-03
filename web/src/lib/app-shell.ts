export type ShellSessionStatus = "loading" | "ready" | "error";

export function showPublicGauge(
  pathname: string,
  gaugePath: string,
  sessionStatus: ShellSessionStatus,
  hasUser: boolean,
): boolean {
  return pathname === gaugePath && sessionStatus === "ready" && !hasUser;
}

export function shouldRedirectExpiredSession(
  expired: boolean,
  sessionStatus: ShellSessionStatus,
  pathname: string,
  loginPath: string,
  isPublicGauge: boolean,
  signupPath?: string,
): boolean {
  return (
    expired &&
    sessionStatus === "ready" &&
    pathname !== loginPath &&
    pathname !== signupPath &&
    !isPublicGauge
  );
}
