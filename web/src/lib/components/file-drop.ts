/**
 * Drag files from the desktop onto a target that already has a file picker.
 *
 * `use:fileDrop` is only an enhancement: the picker button stays the accessible way in, and the
 * dropped files go to the same handler the picker calls, so validation and feedback never differ.
 * `installFileDropGuard` keeps the browser from navigating away when a file lands outside a target.
 */

export interface DropFile {
  name: string;
  type: string;
}

export interface FileDropOptions {
  /** A disabled target shows no affordance and ignores drops. */
  enabled?: boolean;
  /** The picker's `accept` value, such as `image/*` or `.pdf,image/*`. Empty accepts anything. */
  accept?: string;
  /** Hand over every accepted file; otherwise only the first one (single-file pickers). */
  multiple?: boolean;
  /** Short text shown while a file hovers over the target. */
  label: string;
  onFiles: (files: File[]) => void;
  /** Called when files were dropped but none matches `accept`. */
  onRejected?: (files: File[]) => void;
}

const TARGET_ATTRIBUTE = "data-file-drop";
const LABEL_ATTRIBUTE = "data-file-drop-label";

/** Only drags that carry files; text or link drags are left alone. */
export function isFileDrag(
  transfer: { types?: ArrayLike<string> } | null | undefined,
): boolean {
  const types = transfer?.types;
  if (!types) return false;
  return Array.prototype.includes.call(types, "Files");
}

/** Whether a file matches an `accept` list (MIME types, `type/*` wildcards and extensions). */
export function acceptsFile(
  file: DropFile,
  accept: string | undefined,
): boolean {
  const rules = (accept ?? "")
    .split(",")
    .map((rule) => rule.trim().toLowerCase())
    .filter(Boolean);
  if (rules.length === 0) return true;
  const name = file.name.toLowerCase();
  const type = file.type.toLowerCase();
  return rules.some((rule) => {
    if (rule.startsWith(".")) return name.endsWith(rule);
    if (rule.endsWith("/*")) return type.startsWith(rule.slice(0, -1));
    return type === rule;
  });
}

export function partitionFiles<T extends DropFile>(
  files: readonly T[],
  accept: string | undefined,
  multiple: boolean,
): { accepted: T[]; rejected: T[] } {
  const accepted: T[] = [];
  const rejected: T[] = [];
  for (const file of files) {
    if (!acceptsFile(file, accept)) rejected.push(file);
    else if (multiple || accepted.length === 0) accepted.push(file);
  }
  return { accepted, rejected };
}

/**
 * `dragenter` and `dragleave` also fire for every child element, so a plain on/off flag flickers.
 * Counting the nested enters and leaves tells when the pointer really left the target.
 */
export class DragDepth {
  #depth = 0;

  /** True for the enter that starts a drag over the target. */
  enter(): boolean {
    this.#depth += 1;
    return this.#depth === 1;
  }

  /** True when the pointer has left the target. */
  leave(): boolean {
    if (this.#depth === 0) return false;
    this.#depth -= 1;
    return this.#depth === 0;
  }

  get inside(): boolean {
    return this.#depth > 0;
  }

  reset(): void {
    this.#depth = 0;
  }
}

export function fileDrop(node: HTMLElement, initial: FileDropOptions) {
  let options = initial;
  const depth = new DragDepth();

  const enabled = () => options.enabled !== false;

  function sync() {
    if (!enabled()) {
      node.removeAttribute(TARGET_ATTRIBUTE);
      node.removeAttribute(LABEL_ATTRIBUTE);
      return;
    }
    node.setAttribute(TARGET_ATTRIBUTE, depth.inside ? "active" : "idle");
    node.setAttribute(LABEL_ATTRIBUTE, options.label);
  }

  function onEnter(event: DragEvent) {
    if (!enabled() || !isFileDrag(event.dataTransfer)) return;
    event.preventDefault();
    if (depth.enter()) sync();
  }

  function onOver(event: DragEvent) {
    if (!enabled() || !isFileDrag(event.dataTransfer)) return;
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = "copy";
  }

  function onLeave(event: DragEvent) {
    if (!enabled() || !isFileDrag(event.dataTransfer)) return;
    if (depth.leave()) sync();
  }

  function onDrop(event: DragEvent) {
    if (!enabled() || !isFileDrag(event.dataTransfer)) return;
    event.preventDefault();
    depth.reset();
    sync();
    const dropped = Array.from(event.dataTransfer?.files ?? []);
    if (dropped.length === 0) return;
    const { accepted, rejected } = partitionFiles(
      dropped,
      options.accept,
      options.multiple === true,
    );
    if (accepted.length > 0) options.onFiles(accepted);
    else options.onRejected?.(rejected);
  }

  node.addEventListener("dragenter", onEnter);
  node.addEventListener("dragover", onOver);
  node.addEventListener("dragleave", onLeave);
  node.addEventListener("drop", onDrop);
  sync();

  return {
    update(next: FileDropOptions) {
      options = next;
      if (!enabled()) depth.reset();
      sync();
    },
    destroy() {
      node.removeEventListener("dragenter", onEnter);
      node.removeEventListener("dragover", onOver);
      node.removeEventListener("dragleave", onLeave);
      node.removeEventListener("drop", onDrop);
      node.removeAttribute(TARGET_ATTRIBUTE);
      node.removeAttribute(LABEL_ATTRIBUTE);
    },
  };
}

/**
 * A file dropped outside every target would make the browser open it and leave the app. Cancel
 * that for file drags only, and show the "not allowed" cursor outside the targets. Returns the
 * cleanup.
 */
export function installFileDropGuard(target: Window = window): () => void {
  const inTarget = (event: DragEvent) =>
    event.target instanceof Element &&
    event.target.closest(`[${TARGET_ATTRIBUTE}]`) !== null;
  const onOver = (event: DragEvent) => {
    if (!isFileDrag(event.dataTransfer)) return;
    event.preventDefault();
    if (!inTarget(event) && event.dataTransfer)
      event.dataTransfer.dropEffect = "none";
  };
  const onDrop = (event: DragEvent) => {
    if (isFileDrag(event.dataTransfer)) event.preventDefault();
  };
  target.addEventListener("dragover", onOver);
  target.addEventListener("drop", onDrop);
  return () => {
    target.removeEventListener("dragover", onOver);
    target.removeEventListener("drop", onDrop);
  };
}
