/**
 * Whether to offer a "Take photo" button next to a file picker. A file input
 * with `capture` opens the camera app on phones and tablets (coarse primary
 * pointer); on a desktop the browser would just show the file dialog again, so
 * the extra button stays hidden there.
 */
export function cameraCaptureLikely(
  win: Pick<Window, "matchMedia"> | undefined,
): boolean {
  if (!win || typeof win.matchMedia !== "function") return false;
  return win.matchMedia("(pointer: coarse)").matches;
}
