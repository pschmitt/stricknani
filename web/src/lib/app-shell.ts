export type ShellSessionStatus = "loading" | "ready" | "error";

export interface AppShellRoutes {
  projects: string;
  yarns: string;
  more: string;
  imports: string;
  categories: string;
  adminAudit: string;
}

let internalRouteStack: string[] = [];
let internalRouteIndex = -1;

export function resetInternalRouteStack(): void {
  internalRouteStack = [];
  internalRouteIndex = -1;
}

export function trackInternalRoute(
  pathname: string,
  navigationType: string | null,
): void {
  if (pathname !== "/app" && !pathname.startsWith("/app/")) {
    resetInternalRouteStack();
    return;
  }

  if (internalRouteIndex < 0) {
    internalRouteStack = [pathname];
    internalRouteIndex = 0;
    return;
  }

  if (internalRouteStack[internalRouteIndex] === pathname) return;

  if (navigationType === "popstate") {
    if (
      internalRouteIndex > 0 &&
      internalRouteStack[internalRouteIndex - 1] === pathname
    ) {
      internalRouteIndex -= 1;
      return;
    }
    if (
      internalRouteIndex + 1 < internalRouteStack.length &&
      internalRouteStack[internalRouteIndex + 1] === pathname
    ) {
      internalRouteIndex += 1;
      return;
    }
    internalRouteStack = [pathname];
    internalRouteIndex = 0;
    return;
  }

  internalRouteStack = internalRouteStack.slice(0, internalRouteIndex + 1);
  internalRouteStack.push(pathname);
  if (internalRouteStack.length > 64) internalRouteStack.shift();
  internalRouteIndex = internalRouteStack.length - 1;
}

export function hasInternalRouteBack(pathname: string): boolean {
  return (
    internalRouteIndex > 0 &&
    internalRouteStack[internalRouteIndex] === pathname
  );
}

export function internalRouteBackTarget(pathname: string): string | null {
  if (!hasInternalRouteBack(pathname)) return null;
  return internalRouteStack[internalRouteIndex - 1] ?? null;
}

export function parentRouteFallback(
  pathname: string,
  routes: AppShellRoutes,
): string {
  const root = routes.projects.replace(/\/projects\/?$/, "");
  const appPrefix = `${root}/`;
  if (!pathname.startsWith(appPrefix)) return routes.projects;
  const relative = pathname.slice(appPrefix.length);
  const segments = relative.split("/").filter(Boolean);

  if (segments[0] === "projects") {
    if (segments.length >= 3 && segments[2] === "edit") {
      return `${root}/projects/${segments[1]}`;
    }
    return routes.projects;
  }
  if (segments[0] === "yarns") {
    if (segments.length >= 3 && segments[2] === "edit") {
      return `${root}/yarns/${segments[1]}`;
    }
    return routes.yarns;
  }
  if (segments[0] === "admin") {
    if (segments[1] === "resources") return routes.adminAudit;
    if (segments[1] === "audit" && segments.length > 2) {
      return routes.adminAudit;
    }
    return routes.more;
  }
  if (segments[0] === "account") return routes.more;
  if (segments[0] === "imports") {
    return segments.length > 1 ? routes.imports : routes.more;
  }
  if (segments[0] === "categories") {
    return segments.length > 1 ? routes.categories : routes.more;
  }
  if (segments[0] === "gauge") return routes.more;
  return routes.projects;
}

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
): boolean {
  return (
    expired &&
    sessionStatus === "ready" &&
    pathname !== loginPath &&
    !isPublicGauge
  );
}
