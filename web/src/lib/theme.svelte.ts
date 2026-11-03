import {
  applyTheme,
  readTheme,
  themeColorFor,
  THEME_STORAGE_KEY,
  writeTheme,
  type ThemeChoice,
} from "./theme";

export const theme = $state<{ choice: ThemeChoice }>({ choice: "system" });

function syncDocument() {
  if (typeof document === "undefined") return;
  applyTheme(theme.choice, document.documentElement);
  const dark =
    typeof matchMedia === "function" &&
    matchMedia("(prefers-color-scheme: dark)").matches;
  for (const meta of document.querySelectorAll('meta[name="theme-color"]'))
    meta.setAttribute("content", themeColorFor(theme.choice, dark));
}

export function setTheme(choice: ThemeChoice): void {
  theme.choice = choice;
  writeTheme(choice);
  syncDocument();
}

/** Read the stored choice, apply it, and follow changes from other tabs and the OS. */
export function initTheme(): () => void {
  theme.choice = readTheme();
  syncDocument();
  const onStorage = (event: StorageEvent) => {
    if (event.key !== null && event.key !== THEME_STORAGE_KEY) return;
    theme.choice = readTheme();
    syncDocument();
  };
  const media =
    typeof matchMedia === "function"
      ? matchMedia("(prefers-color-scheme: dark)")
      : null;
  window.addEventListener("storage", onStorage);
  media?.addEventListener("change", syncDocument);
  return () => {
    window.removeEventListener("storage", onStorage);
    media?.removeEventListener("change", syncDocument);
  };
}
