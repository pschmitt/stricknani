/** Open state of the command palette ("search everything"), shared by the shortcut and the buttons. */
export const palette = $state({ open: false });

export function openPalette(): void {
  palette.open = true;
}

export function closePalette(): void {
  palette.open = false;
}

export function togglePalette(): void {
  palette.open = !palette.open;
}

/** The shortcut as people read it: Cmd K on Apple devices, Ctrl K elsewhere. */
export function shortcutLabel(): string {
  const apple =
    typeof navigator !== "undefined" &&
    /mac|iphone|ipad|ipod/i.test(navigator.platform || navigator.userAgent);
  return apple ? "⌘K" : "Ctrl K";
}
