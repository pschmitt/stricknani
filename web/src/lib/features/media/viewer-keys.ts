/**
 * Keyboard navigation of the photo viewer. Left/Right step like the on-screen
 * buttons (wrapping around), Home/End jump to the first/last photo. Returns the
 * index to show, or null when the key is not handled.
 */
export function viewerKeyTarget(
  key: string,
  index: number,
  count: number,
): number | null {
  if (count < 2 || index < 0 || index >= count) return null;
  switch (key) {
    case "ArrowLeft":
      return (index - 1 + count) % count;
    case "ArrowRight":
      return (index + 1) % count;
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
}

/** True when the element takes its own arrow/Home/End keys (text editing, selects). */
export function isTextEntryTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
}
