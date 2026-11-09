import { describe, expect, it, vi } from "vitest";
import {
  acceptsFile,
  DragDepth,
  fileDrop,
  type FileDropOptions,
  installFileDropGuard,
  isFileDrag,
  partitionFiles,
} from "./file-drop";

type Listener = (event: unknown) => void;

function fakeNode() {
  const listeners = new Map<string, Listener>();
  const attributes = new Map<string, string>();
  const node = {
    addEventListener: (name: string, listener: Listener) =>
      listeners.set(name, listener),
    removeEventListener: (name: string) => listeners.delete(name),
    setAttribute: (name: string, value: string) => attributes.set(name, value),
    removeAttribute: (name: string) => attributes.delete(name),
    getAttribute: (name: string) => attributes.get(name) ?? null,
  };
  return { node: node as unknown as HTMLElement, listeners, attributes };
}

function dragEvent(types: string[], files: unknown[] = []) {
  return {
    preventDefault: vi.fn(),
    dataTransfer: { types, files, dropEffect: "none" },
  };
}

const image = { name: "a.PNG", type: "image/png" };
const pdf = { name: "b.pdf", type: "application/pdf" };
const html = { name: "c.html", type: "" };

describe("file drop helpers", () => {
  it("recognises drags that carry files", () => {
    expect(isFileDrag({ types: ["Files"] })).toBe(true);
    expect(isFileDrag({ types: ["text/plain", "Files"] })).toBe(true);
    expect(isFileDrag({ types: ["text/plain", "text/uri-list"] })).toBe(false);
    expect(isFileDrag(null)).toBe(false);
    expect(isFileDrag({})).toBe(false);
  });

  it("matches the picker accept list", () => {
    expect(acceptsFile(image, "image/*")).toBe(true);
    expect(acceptsFile(pdf, "image/*")).toBe(false);
    expect(acceptsFile(pdf, ".html, .pdf")).toBe(true);
    expect(acceptsFile(html, ".html,.htm,.pdf,image/*")).toBe(true);
    expect(acceptsFile(image, ".PNG")).toBe(true);
    expect(acceptsFile(pdf, "application/pdf")).toBe(true);
    expect(acceptsFile(pdf, "")).toBe(true);
    expect(acceptsFile(pdf, undefined)).toBe(true);
  });

  it("keeps the first accepted file for single-file targets", () => {
    const files = [pdf, image, { name: "d.jpg", type: "image/jpeg" }];
    const single = partitionFiles(files, "image/*", false);
    expect(single.accepted).toEqual([image]);
    expect(single.rejected).toEqual([pdf]);
    const many = partitionFiles(files, "image/*", true);
    expect(many.accepted).toHaveLength(2);
  });

  it("counts nested enters so the highlight does not flicker", () => {
    const depth = new DragDepth();
    expect(depth.enter()).toBe(true);
    expect(depth.enter()).toBe(false);
    expect(depth.leave()).toBe(false);
    expect(depth.inside).toBe(true);
    expect(depth.leave()).toBe(true);
    expect(depth.inside).toBe(false);
    expect(depth.leave()).toBe(false);
    depth.enter();
    depth.reset();
    expect(depth.inside).toBe(false);
  });
});

describe("fileDrop action", () => {
  function setup(extra: Partial<FileDropOptions> = {}) {
    const dom = fakeNode();
    const onFiles = vi.fn();
    const onRejected = vi.fn();
    const action = fileDrop(dom.node, {
      accept: "image/*",
      label: "Drop to add photos",
      onFiles,
      onRejected,
      ...extra,
    });
    return { ...dom, onFiles, onRejected, action };
  }

  it("marks the target and highlights only for file drags", () => {
    const { listeners, attributes } = setup();
    expect(attributes.get("data-file-drop")).toBe("idle");
    expect(attributes.get("data-file-drop-label")).toBe("Drop to add photos");

    const textDrag = dragEvent(["text/plain"]);
    listeners.get("dragenter")?.(textDrag);
    expect(textDrag.preventDefault).not.toHaveBeenCalled();
    expect(attributes.get("data-file-drop")).toBe("idle");

    const fileDrag = dragEvent(["Files"]);
    listeners.get("dragenter")?.(fileDrag);
    expect(fileDrag.preventDefault).toHaveBeenCalled();
    expect(attributes.get("data-file-drop")).toBe("active");

    const over = dragEvent(["Files"]);
    listeners.get("dragover")?.(over);
    expect(over.dataTransfer.dropEffect).toBe("copy");
  });

  it("stays highlighted across child enter/leave pairs", () => {
    const { listeners, attributes } = setup();
    listeners.get("dragenter")?.(dragEvent(["Files"]));
    listeners.get("dragenter")?.(dragEvent(["Files"]));
    listeners.get("dragleave")?.(dragEvent(["Files"]));
    expect(attributes.get("data-file-drop")).toBe("active");
    listeners.get("dragleave")?.(dragEvent(["Files"]));
    expect(attributes.get("data-file-drop")).toBe("idle");
  });

  it("routes accepted files to the handler and clears the highlight", () => {
    const { listeners, attributes, onFiles, onRejected } = setup();
    listeners.get("dragenter")?.(dragEvent(["Files"]));
    listeners.get("drop")?.(dragEvent(["Files"], [pdf, image]));
    expect(onFiles).toHaveBeenCalledWith([image]);
    expect(onRejected).not.toHaveBeenCalled();
    expect(attributes.get("data-file-drop")).toBe("idle");
  });

  it("reports unsupported files", () => {
    const { listeners, onFiles, onRejected } = setup();
    listeners.get("drop")?.(dragEvent(["Files"], [pdf]));
    expect(onFiles).not.toHaveBeenCalled();
    expect(onRejected).toHaveBeenCalledWith([pdf]);
  });

  it("ignores everything while disabled and drops the marker", () => {
    const { listeners, attributes, onFiles, action } = setup({
      enabled: false,
    });
    expect(attributes.has("data-file-drop")).toBe(false);
    const event = dragEvent(["Files"], [image]);
    listeners.get("drop")?.(event);
    expect(event.preventDefault).not.toHaveBeenCalled();
    expect(onFiles).not.toHaveBeenCalled();

    action.update({ label: "Drop", onFiles });
    expect(attributes.get("data-file-drop")).toBe("idle");
    action.update({ label: "Drop", onFiles, enabled: false });
    expect(attributes.has("data-file-drop")).toBe(false);
  });

  it("removes its listeners on destroy", () => {
    const { listeners, attributes, action } = setup();
    action.destroy();
    expect(listeners.size).toBe(0);
    expect(attributes.size).toBe(0);
  });
});

describe("page-level guard", () => {
  class FakeElement {
    constructor(private readonly inside: boolean) {}
    closest() {
      return this.inside ? this : null;
    }
  }

  function install() {
    vi.stubGlobal("Element", FakeElement);
    const listeners = new Map<string, Listener>();
    const target = {
      addEventListener: (name: string, listener: Listener) =>
        listeners.set(name, listener),
      removeEventListener: (name: string) => listeners.delete(name),
    } as unknown as Window;
    const stop = installFileDropGuard(target);
    return { listeners, stop };
  }

  it("cancels file drags and shows no-drop outside targets", () => {
    const { listeners, stop } = install();
    const outside = {
      ...dragEvent(["Files"]),
      target: new FakeElement(false),
    };
    listeners.get("dragover")?.(outside);
    expect(outside.preventDefault).toHaveBeenCalled();
    expect(outside.dataTransfer.dropEffect).toBe("none");

    const inside = { ...dragEvent(["Files"]), target: new FakeElement(true) };
    inside.dataTransfer.dropEffect = "copy";
    listeners.get("dragover")?.(inside);
    expect(inside.dataTransfer.dropEffect).toBe("copy");

    const drop = { ...dragEvent(["Files"]), target: new FakeElement(false) };
    listeners.get("drop")?.(drop);
    expect(drop.preventDefault).toHaveBeenCalled();

    stop();
    expect(listeners.size).toBe(0);
    vi.unstubAllGlobals();
  });

  it("leaves other drags alone", () => {
    const { listeners, stop } = install();
    const text = {
      ...dragEvent(["text/plain"]),
      target: new FakeElement(false),
    };
    listeners.get("dragover")?.(text);
    listeners.get("drop")?.(text);
    expect(text.preventDefault).not.toHaveBeenCalled();
    stop();
    vi.unstubAllGlobals();
  });
});
