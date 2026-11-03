/** Per-browser colour theme: System, Light or Dark, kept in localStorage. */

export type ThemeChoice = "system" | "light" | "dark";

export const THEME_STORAGE_KEY = "stricknani.theme";
export const themeChoices: readonly ThemeChoice[] = ["system", "light", "dark"];

/** Canvas colours of the two palettes, used for the browser chrome (theme-color). */
export const themeColors = { light: "#f7f3fc", dark: "#1a1421" } as const;

type ThemeStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function parseTheme(value: unknown): ThemeChoice {
  return value === "light" || value === "dark" ? value : "system";
}

function defaultStorage(): ThemeStorage | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}

/** The stored choice, or System when nothing usable is stored or storage is unavailable. */
export function readTheme(storage: ThemeStorage | null = defaultStorage()) {
  try {
    return parseTheme(storage?.getItem(THEME_STORAGE_KEY));
  } catch {
    return "system";
  }
}

/** Persist the choice; System clears the key. Returns false when storage refused. */
export function writeTheme(
  choice: ThemeChoice,
  storage: ThemeStorage | null = defaultStorage(),
): boolean {
  if (!storage) return false;
  try {
    if (choice === "system") storage.removeItem(THEME_STORAGE_KEY);
    else storage.setItem(THEME_STORAGE_KEY, choice);
    return true;
  } catch {
    return false;
  }
}

/** Set `data-theme` for Light and Dark and remove it for System. */
export function applyTheme(
  choice: ThemeChoice,
  root: Pick<HTMLElement, "setAttribute" | "removeAttribute">,
): void {
  if (choice === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", choice);
}

export function themeColorFor(
  choice: ThemeChoice,
  systemPrefersDark: boolean,
): string {
  const dark = choice === "dark" || (choice === "system" && systemPrefersDark);
  return dark ? themeColors.dark : themeColors.light;
}
