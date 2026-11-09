// The remembered Visual / Raw choice of the Markdown editors, per viewer in localStorage.
// Every access is guarded: private mode, blocked storage and SSR fall back to Visual.

export type EditorMode = "visual" | "raw";
export const EDITOR_MODE_STORAGE_KEY = "stricknani.markdownEditorMode";

type StorageLike = Pick<Storage, "getItem" | "setItem">;

function defaultStorage(): StorageLike | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}

export function readEditorMode(
  storage: StorageLike | null = defaultStorage(),
): EditorMode {
  try {
    return storage?.getItem(EDITOR_MODE_STORAGE_KEY) === "raw"
      ? "raw"
      : "visual";
  } catch {
    return "visual";
  }
}

export function writeEditorMode(
  mode: EditorMode,
  storage: StorageLike | null = defaultStorage(),
): void {
  try {
    storage?.setItem(EDITOR_MODE_STORAGE_KEY, mode);
  } catch {
    // Storage unavailable: the choice lasts for this page only.
  }
}
