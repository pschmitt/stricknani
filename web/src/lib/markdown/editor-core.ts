// The ProseMirror editor behind MarkdownEditor.svelte. Loaded on demand (dynamic import), so
// pages that only read text do not carry it. It emits the same Markdown string a textarea
// would hold and never re-emits text on load: only edits by the person produce a change.
import {
  baseKeymap,
  chainCommands,
  createParagraphNear,
  liftEmptyBlock,
  newlineInCode,
  setBlockType,
  splitBlock,
  toggleMark,
} from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import {
  InputRule,
  inputRules,
  textblockTypeInputRule,
  wrappingInputRule,
} from "prosemirror-inputrules";
import { keymap } from "prosemirror-keymap";
import { Fragment, Slice, type MarkType, type Node } from "prosemirror-model";
import {
  liftListItem,
  sinkListItem,
  splitListItem,
  wrapInList,
} from "prosemirror-schema-list";
import { EditorState, Plugin, TextSelection } from "prosemirror-state";
import { EditorView } from "prosemirror-view";
import {
  docToMarkdown,
  markdownSchema,
  markdownToDoc,
} from "./markdown-model.ts";

export type EditorCommand =
  "bold" | "italic" | "strike" | "heading" | "bullet" | "ordered";

export type ActiveFormats = {
  bold: boolean;
  italic: boolean;
  strike: boolean;
  heading: boolean;
  bullet: boolean;
  ordered: boolean;
  link: boolean;
};

export type EditorOptions = {
  parent: HTMLElement;
  markdown: string;
  /** Every line break stays a line break (list-like fields). */
  lines: boolean;
  editable: boolean;
  placeholder: string;
  id: string;
  label: string;
  labelledBy?: string;
  describedBy?: string;
  onChange: (markdown: string) => void;
  onFormats: (formats: ActiveFormats) => void;
  /** Ctrl/Cmd+K. */
  onLinkShortcut: () => void;
  onFocusChange?: (focused: boolean) => void;
};

export type EditorHandle = {
  focus: () => void;
  /** Replace the document from outside (a reloaded draft). Does not call onChange. */
  setMarkdown: (markdown: string) => void;
  run: (command: EditorCommand) => void;
  linkHref: () => string | null;
  setLink: (href: string | null) => void;
  setEditable: (editable: boolean) => void;
  destroy: () => void;
};

const HEADING_LEVEL = 3;
const schema = markdownSchema;
const EXTERNAL = "markdown-editor-external";

function markActive(state: EditorState, type: MarkType): boolean {
  const { from, $from, to, empty } = state.selection;
  if (empty) return Boolean(type.isInSet(state.storedMarks || $from.marks()));
  return state.doc.rangeHasMark(from, to, type);
}

function insideList(state: EditorState, listName: string): boolean {
  const { $from } = state.selection;
  for (let depth = $from.depth; depth > 0; depth--) {
    if ($from.node(depth).type.name === listName) return true;
  }
  return false;
}

function activeFormats(state: EditorState): ActiveFormats {
  return {
    bold: markActive(state, schema.marks.strong),
    italic: markActive(state, schema.marks.em),
    strike: markActive(state, schema.marks.strike),
    heading: state.selection.$from.parent.type === schema.nodes.heading,
    bullet: insideList(state, "bullet_list"),
    ordered: insideList(state, "ordered_list"),
    link: markActive(state, schema.marks.link),
  };
}

type Command = (
  state: EditorState,
  dispatch?: EditorView["dispatch"],
) => boolean;

function toggleList(name: "bullet_list" | "ordered_list"): Command {
  const listType = schema.nodes[name];
  const item = schema.nodes.list_item;
  return (state, dispatch) => {
    if (insideList(state, name)) return liftListItem(item)(state, dispatch);
    if (insideList(state, "bullet_list") || insideList(state, "ordered_list")) {
      // Convert the other kind: lift out, then wrap again.
      let next = state;
      const lifted = liftListItem(item)(state, (tr) => {
        next = state.apply(tr);
      });
      if (!lifted) return false;
      return wrapInList(listType)(next, dispatch);
    }
    return wrapInList(listType)(state, dispatch);
  };
}

const toggleHeading: Command = (state, dispatch) =>
  state.selection.$from.parent.type === schema.nodes.heading
    ? setBlockType(schema.nodes.paragraph)(state, dispatch)
    : setBlockType(schema.nodes.heading, { level: HEADING_LEVEL })(
        state,
        dispatch,
      );

const COMMANDS: Record<EditorCommand, Command> = {
  bold: toggleMark(schema.marks.strong),
  italic: toggleMark(schema.marks.em),
  strike: toggleMark(schema.marks.strike),
  heading: toggleHeading,
  bullet: toggleList("bullet_list"),
  ordered: toggleList("ordered_list"),
};

/** The text range of the link around the cursor. */
function linkRangeAt(state: EditorState): { from: number; to: number } | null {
  const { $from } = state.selection;
  const type = schema.marks.link;
  const start = $from.start();
  let found: { from: number; to: number } | null = null;
  let run: { from: number; to: number; href: unknown } | null = null;
  const flush = () => {
    if (run && $from.pos >= run.from && $from.pos <= run.to && !found) {
      found = { from: run.from, to: run.to };
    }
    run = null;
  };
  $from.parent.forEach((child, offset) => {
    const mark = type.isInSet(child.marks);
    const from = start + offset;
    if (mark && run && run.href === mark.attrs.href) {
      run.to = from + child.nodeSize;
      return;
    }
    flush();
    if (mark) run = { from, to: from + child.nodeSize, href: mark.attrs.href };
  });
  flush();
  return found;
}

function buildInputRules(): Plugin {
  const rules: InputRule[] = [
    wrappingInputRule(/^\s*([-+*])\s$/, schema.nodes.bullet_list),
    wrappingInputRule(
      /^(\d+)\.\s$/,
      schema.nodes.ordered_list,
      (match) => ({ order: Number(match[1]) }),
      (match, node) => node.childCount + node.attrs.order === Number(match[1]),
    ),
    textblockTypeInputRule(/^(#{1,6})\s$/, schema.nodes.heading, (match) => ({
      level: match[1].length,
    })),
  ];
  return inputRules({ rules });
}

/** Plain text is Markdown: pasting "- a" or "**b**" keeps its formatting. */
function pastedMarkdown(text: string, lines: boolean, plain: boolean): Slice {
  if (plain) {
    const paragraphs = text
      .split(/\n{2,}/)
      .map((chunk) =>
        schema.nodes.paragraph.create(
          null,
          chunk ? schema.text(chunk.replace(/\n/g, " ")) : null,
        ),
      );
    return new Slice(Fragment.from(paragraphs), 1, 1);
  }
  const doc = markdownToDoc(text, lines);
  const single =
    doc.childCount === 1 && doc.firstChild?.type.name === "paragraph";
  return new Slice(doc.content, single ? 1 : 0, single ? 1 : 0);
}

export function createMarkdownEditor(options: EditorOptions): EditorHandle {
  let editable = options.editable;
  const doc = markdownToDoc(options.markdown, options.lines);

  const emptyPlaceholder = new Plugin({
    props: {
      attributes: (state) => {
        const empty =
          state.doc.childCount === 1 &&
          state.doc.firstChild?.isTextblock === true &&
          state.doc.firstChild.content.size === 0 &&
          state.doc.firstChild.type === schema.nodes.paragraph;
        return {
          class: empty
            ? "md-editor__content md-editor__content--empty"
            : "md-editor__content",
        };
      },
    },
  });

  const view: EditorView = new EditorView(options.parent, {
    state: EditorState.create({
      doc,
      plugins: [
        buildInputRules(),
        history(),
        keymap({
          "Mod-z": undo,
          "Mod-y": redo,
          "Shift-Mod-z": redo,
          "Mod-b": COMMANDS.bold,
          "Mod-i": COMMANDS.italic,
          "Mod-k": () => {
            options.onLinkShortcut();
            return true;
          },
          Enter: chainCommands(
            splitListItem(schema.nodes.list_item),
            newlineInCode,
            createParagraphNear,
            liftEmptyBlock,
            splitBlock,
          ),
          "Shift-Enter": (state, dispatch) => {
            dispatch?.(
              state.tr
                .replaceSelectionWith(schema.nodes.hard_break.create())
                .scrollIntoView(),
            );
            return true;
          },
          "Mod-]": sinkListItem(schema.nodes.list_item),
          "Mod-[": liftListItem(schema.nodes.list_item),
        }),
        keymap(baseKeymap),
        emptyPlaceholder,
      ],
    }),
    editable: () => editable,
    attributes: () => ({
      id: options.id,
      role: "textbox",
      "aria-multiline": "true",
      ...(options.labelledBy
        ? { "aria-labelledby": options.labelledBy }
        : { "aria-label": options.label }),
      ...(options.describedBy
        ? { "aria-describedby": options.describedBy }
        : {}),
      ...(editable ? {} : { "aria-readonly": "true" }),
      "data-placeholder": options.placeholder,
      spellcheck: "true",
    }),
    clipboardTextParser: (text, _context, plain) =>
      pastedMarkdown(text, options.lines, plain),
    handleDOMEvents: {
      focus: () => {
        options.onFocusChange?.(true);
        return false;
      },
      blur: () => {
        options.onFocusChange?.(false);
        return false;
      },
    },
    dispatchTransaction(tr) {
      const next = view.state.apply(tr);
      view.updateState(next);
      options.onFormats(activeFormats(next));
      if (tr.docChanged && !tr.getMeta(EXTERNAL)) {
        options.onChange(docToMarkdown(next.doc, options.lines));
      }
    },
  });

  options.onFormats(activeFormats(view.state));

  return {
    focus: () => view.focus(),
    setMarkdown(markdown) {
      if (
        docToMarkdown(view.state.doc, options.lines) ===
        markdown.replace(/\s+$/, "")
      ) {
        return;
      }
      const next = markdownToDoc(markdown, options.lines);
      const tr = view.state.tr.replaceWith(
        0,
        view.state.doc.content.size,
        next.content,
      );
      tr.setMeta(EXTERNAL, true).setMeta("addToHistory", false);
      tr.setSelection(TextSelection.atEnd(tr.doc));
      view.dispatch(tr);
    },
    run(command) {
      COMMANDS[command](view.state, view.dispatch);
      view.focus();
    },
    linkHref() {
      const { from, $from, empty } = view.state.selection;
      const type = schema.marks.link;
      const mark = empty
        ? type.isInSet($from.marks())
        : type.isInSet(view.state.doc.resolve(from + 1).marks());
      return mark ? String(mark.attrs.href) : null;
    },
    setLink(href) {
      const { state } = view;
      const type = schema.marks.link;
      let { from, to } = state.selection;
      if (from === to) {
        const range = linkRangeAt(state);
        if (range) ({ from, to } = range);
      }
      let tr = state.tr;
      if (!href) {
        tr = tr.removeMark(from, to, type);
      } else {
        if (from === to) {
          tr = tr.insertText(href, from);
          to = from + href.length;
        }
        tr = tr
          .removeMark(from, to, type)
          .addMark(from, to, type.create({ href }));
      }
      view.dispatch(tr.scrollIntoView());
      view.focus();
    },
    setEditable(next) {
      editable = next;
      view.setProps({ editable: () => editable });
    },
    destroy: () => view.destroy(),
  };
}

export type { Node };
