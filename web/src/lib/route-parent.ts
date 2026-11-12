function normalizePath(pathname: string): string | null {
  if (!pathname.startsWith("/")) return null;
  return pathname.replace(/\/+$/, "") || "/";
}

export function routeParent(pathname: string, basePath: string): string | null {
  const currentPath = normalizePath(pathname);
  const normalizedBase =
    basePath === "" || basePath === "/" ? "" : normalizePath(basePath);
  if (currentPath === null || normalizedBase === null) return null;

  const base = normalizedBase;
  if (
    base !== "" &&
    currentPath !== base &&
    !currentPath.startsWith(`${base}/`)
  ) {
    return null;
  }

  const route =
    base === "" ? currentPath : currentPath.slice(base.length) || "/";
  let parent: string | null = null;

  if (route === "/privacy") {
    parent = "/about";
  } else if (
    route === "/about" ||
    route === "/activity" ||
    route === "/backups" ||
    route === "/trash"
  ) {
    parent = "/more";
  } else if (route === "/imports") {
    parent = "/create";
  } else if (route.startsWith("/imports/")) {
    parent = "/imports";
  } else if (route === "/projects/new" || /^\/projects\/[^/]+$/.test(route)) {
    parent = "/projects";
  } else {
    const projectEdit = route.match(/^\/projects\/([^/]+)\/edit$/);
    if (projectEdit) parent = `/projects/${projectEdit[1]}`;
  }

  if (parent === null) {
    if (route === "/yarns/new" || /^\/yarns\/[^/]+$/.test(route)) {
      parent = "/yarns";
    } else {
      const yarnEdit = route.match(/^\/yarns\/([^/]+)\/edit$/);
      if (yarnEdit) parent = `/yarns/${yarnEdit[1]}`;
    }
  }

  if (parent === null && /^\/admin\/audit\/[^/]+$/.test(route)) {
    parent = "/admin/audit";
  }
  if (parent === null && /^\/admin\/resources\/[^/]+\/[^/]+$/.test(route)) {
    parent = "/admin/audit";
  }

  return parent === null ? null : `${base}${parent}` || "/";
}
