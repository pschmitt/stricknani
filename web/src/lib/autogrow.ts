/**
 * Every text area grows with its content. Browsers with CSS `field-sizing`
 * do it in `app.css`; for the others this sizes text areas when they appear and
 * on input. Returns the cleanup.
 */
export function installAutogrow(root: Document = document): () => void {
  if (typeof CSS !== "undefined" && CSS.supports("field-sizing", "content")) {
    return () => undefined;
  }
  const fit = (area: HTMLTextAreaElement) => {
    area.style.height = "auto";
    area.style.height = `${area.scrollHeight + 2}px`;
  };
  const fitAll = () =>
    root.querySelectorAll("textarea").forEach((area) => fit(area));
  const onInput = (event: Event) => {
    if (event.target instanceof HTMLTextAreaElement) fit(event.target);
  };
  root.addEventListener("input", onInput);
  // Only new text areas: their value is set in the same tick, so size them on the next frame.
  const observer = new MutationObserver((records) => {
    const added: HTMLTextAreaElement[] = [];
    for (const record of records) {
      record.addedNodes.forEach((node) => {
        if (node instanceof HTMLTextAreaElement) added.push(node);
        else if (node instanceof Element)
          added.push(...node.querySelectorAll("textarea"));
      });
    }
    if (added.length) requestAnimationFrame(() => added.forEach(fit));
  });
  observer.observe(root.body, { childList: true, subtree: true });
  fitAll();
  return () => {
    root.removeEventListener("input", onInput);
    observer.disconnect();
  };
}
