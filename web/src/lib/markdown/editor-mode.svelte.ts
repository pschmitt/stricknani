// Shared reactive Visual / Raw choice, so every Markdown editor on a page switches together.
import {
  readEditorMode,
  writeEditorMode,
  type EditorMode,
} from "./editor-mode.ts";

let mode = $state<EditorMode>("visual");
let loaded = false;

export const editorMode = {
  get value(): EditorMode {
    return mode;
  },
  /** Read the remembered choice once, in the browser (call from onMount). */
  load(): void {
    if (loaded) return;
    loaded = true;
    mode = readEditorMode();
  },
  set(next: EditorMode): void {
    loaded = true;
    mode = next;
    writeEditorMode(next);
  },
};
