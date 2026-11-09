/** Pure helpers behind the phone bottom bar and the "More" destinations. */

/** Routes (without the app base path) that live under the More tab. */
export const MORE_SECTION_PATHS = [
  "/more",
  "/create",
  "/imports",
  "/categories",
  "/gauge",
  "/admin",
  "/about",
  "/account",
  "/delete-account",
] as const;

/** Whether `pathname` is `fullPath` itself or a page below it. */
export function isPathActive(
  pathname: string,
  fullPath: string,
  exactOnly = false,
): boolean {
  if (exactOnly) return pathname === fullPath;
  return pathname === fullPath || pathname.startsWith(`${fullPath}/`);
}

/** Whether the More tab is the current destination (More itself or any page under it). */
export function isMoreActive(
  pathname: string,
  resolvePath: (path: string) => string,
): boolean {
  const clean = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  return MORE_SECTION_PATHS.some((path) =>
    isPathActive(clean, resolvePath(path)),
  );
}
