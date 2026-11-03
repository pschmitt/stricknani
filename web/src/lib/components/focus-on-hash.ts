import type { Action } from "svelte/action";

// Svelte action for an editor field that a link can target with a URL fragment
// (for example the sticky-note "Add a note" link). The editors render after
// their data has loaded, so the browser's own fragment scroll has already
// missed; focus the field when it mounts instead.
export const focusOnHash: Action<HTMLElement> = (node) => {
  if (typeof window === "undefined" || !node.id) return;
  if (window.location.hash !== `#${node.id}`) return;
  const frame = requestAnimationFrame(() => {
    node.scrollIntoView({ block: "center" });
    node.focus({ preventScroll: true });
  });
  return { destroy: () => cancelAnimationFrame(frame) };
};
